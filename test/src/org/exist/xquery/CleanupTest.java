/*
 * eXist Open Source Native XML Database
 * Copyright (C) 2001-2017 The eXist Project
 * http://exist-db.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */

package org.exist.xquery;

import org.exist.EXistException;
import org.exist.dom.QName;
import org.exist.dom.persistent.DocumentSet;
import org.exist.security.PermissionDeniedException;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.test.ExistXmldbEmbeddedServer;
import org.exist.xmldb.EXistResource;
import org.exist.xmldb.XQueryService;
import org.exist.xquery.value.FunctionReference;
import org.exist.xquery.value.Sequence;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.xmldb.api.base.*;
import org.xmldb.api.modules.CollectionManagementService;

import java.util.List;
import java.util.Optional;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNull;

/**
 * Test if inline functions and functions defined in imported modules are properly reset.
 *
 * @author Wolfgang
 */
public class CleanupTest {

    private final static String MODULE_NS = "http://exist-db.org/test";

    private final static String TEST_MODULE = "module namespace t=\"" + MODULE_NS + "\";" +
            "declare variable $t:VAR := 123;" +
            "declare function t:test($a) { $a };" +
            "declare function t:unused($a) { $a };" +
            "declare function t:inline($a) { function() { $a } };";

    private final static String TEST_QUERY = "import module namespace t=\"" + MODULE_NS + "\" at " +
            "\"xmldb:exist:///db/test/test-module.xql\";" +
            "t:test('Hello world')";

    private final static String TEST_INLINE = "let $a := \"a\"\n" +
            "let $func := function() { $a }\n" +
            "return\n" +
            "   $func";

    private Collection collection;

    @ClassRule
    public static final ExistXmldbEmbeddedServer existEmbeddedServer = new ExistXmldbEmbeddedServer(false, true);

    @Before
    public void setup() throws XMLDBException {
        final CollectionManagementService service =
                (CollectionManagementService) existEmbeddedServer.getRoot().getService("CollectionManagementService", "1.0");
        collection = service.createCollection("test");
        final Resource doc = collection.createResource("test-module.xql", "BinaryResource");
        doc.setContent(TEST_MODULE);
        ((EXistResource) doc).setMimeType("application/xquery");
        collection.storeResource(doc);
    }

    @After
    public void tearDown() throws XMLDBException {
        final CollectionManagementService service =
                (CollectionManagementService) existEmbeddedServer.getRoot().getService("CollectionManagementService", "1.0");
        service.removeCollection("test");
    }

    @Test
    public void resetStateOfUnusedModuleMembers() throws XMLDBException, XPathException {
        final XQueryService service = (XQueryService)collection.getService("XQueryService", "1.0");
        final CompiledExpression compiled = service.compile(TEST_QUERY);

        final Module module = ((PathExpr) compiled).getContext().getModule(MODULE_NS);
        final UserDefinedFunction unusedFunc = ((ExternalModule)module).getFunction(new QName("unused", MODULE_NS, "t"), 1, ((ExternalModule) module).getContext());
        final java.util.Collection<VariableDeclaration> varDecls = ((ExternalModule) module).getVariableDeclarations();
        final VariableDeclaration var = varDecls.iterator().next();
        final Expression unusedBody = unusedFunc.getFunctionBody();
        final FunctionCall root = (FunctionCall) ((PathExpr) compiled).getFirst();
        final UserDefinedFunction calledFunc = root.getFunction();
        final Expression calledBody = calledFunc.getFunctionBody();

        // set some property so we can test if it gets cleared
        calledFunc.setContextDocSet(DocumentSet.EMPTY_DOCUMENT_SET);
        calledBody.setContextDocSet(DocumentSet.EMPTY_DOCUMENT_SET);
        unusedBody.setContextDocSet(DocumentSet.EMPTY_DOCUMENT_SET);
        var.setContextDocSet(DocumentSet.EMPTY_DOCUMENT_SET);

        // execute query and check result
        final ResourceSet result = service.execute(compiled);
        assertEquals(result.getSize(), 1);
        assertEquals(result.getResource(0).getContent(), "Hello world");

        Sequence[] args = calledFunc.getCurrentArguments();
        assertNull(args);
        assertNull(calledFunc.getContextDocSet());
        assertNull(calledBody.getContextDocSet());
        args = unusedFunc.getCurrentArguments();
        assertNull(args);
        assertNull(unusedBody.getContextDocSet());
        assertNull(unusedFunc.getContextDocSet());
        assertNull(var.getContextDocSet());
    }

    @Test
    public void resetStateOfInlineFunc() throws XMLDBException, EXistException, PermissionDeniedException, XPathException {
        final BrokerPool pool = BrokerPool.getInstance();
        final XQuery xquery = pool.getXQueryService();
        try (final DBBroker broker = pool.get(Optional.of(pool.getSecurityManager().getSystemSubject()))) {
            // execute query to get a function item
            final Sequence result = xquery.execute(broker, TEST_INLINE, Sequence.EMPTY_SEQUENCE);
            assertEquals(result.getItemCount(), 1);
            final FunctionCall call = ((FunctionReference)result.itemAt(0)).getCall();
            // closure variables are set when function item is created, but should be cleared after query
            final List<ClosureVariable> closure = call.getFunction().getClosureVariables();
            assertNull(closure);
        }
    }
}
