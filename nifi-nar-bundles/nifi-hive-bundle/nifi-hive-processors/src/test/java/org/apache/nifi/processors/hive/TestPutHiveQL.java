package org.apache.nifi.processors.hive;/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.dbcp.hive.HiveDBCPService;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestPutHiveQL {
    private static final String createPersons = "CREATE TABLE PERSONS (id integer primary key, name varchar(100), code integer)";
    private static final String createPersonsAutoId = "CREATE TABLE PERSONS (id INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY (START WITH 1), name VARCHAR(100), code INTEGER check(code <= 100))";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void setup() {
        System.setProperty("derby.stream.error.file", "target/derby.log");
    }

    @Test
    public void testDirectStatements() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersons);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");
        runner.enqueue("INSERT INTO PERSONS (ID, NAME, CODE) VALUES (1, 'Mark', 84)".getBytes());
        runner.run();

        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_SUCCESS, 1);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("Mark", rs.getString(2));
                assertEquals(84, rs.getInt(3));
                assertFalse(rs.next());
            }
        }

        runner.enqueue("UPDATE PERSONS SET NAME='George' WHERE ID=1".getBytes());
        runner.run();

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("George", rs.getString(2));
                assertEquals(84, rs.getInt(3));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    public void testFailInMiddleWithBadStatement() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersonsAutoId);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");
        runner.enqueue("INSERT INTO PERSONS (NAME, CODE) VALUES ('Mark', 84)".getBytes());
        runner.enqueue("INSERT INTO PERSONS".getBytes()); // intentionally wrong syntax
        runner.enqueue("INSERT INTO PERSONS (NAME, CODE) VALUES ('Tom', 3)".getBytes());
        runner.enqueue("INSERT INTO PERSONS (NAME, CODE) VALUES ('Harry', 44)".getBytes());
        runner.run();

        runner.assertTransferCount(PutHiveQL.REL_FAILURE, 1);
        runner.assertTransferCount(PutHiveQL.REL_SUCCESS, 3);
    }


    @Test
    public void testFailInMiddleWithBadParameterType() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersonsAutoId);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");

        final Map<String, String> goodAttributes = new HashMap<>();
        goodAttributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        goodAttributes.put("hiveql.args.1.value", "84");

        final Map<String, String> badAttributes = new HashMap<>();
        badAttributes.put("hiveql.args.1.type", String.valueOf(Types.VARCHAR));
        badAttributes.put("hiveql.args.1.value", "hello");

        final byte[] data = "INSERT INTO PERSONS (NAME, CODE) VALUES ('Mark', ?)".getBytes();
        runner.enqueue(data, goodAttributes);
        runner.enqueue(data, badAttributes);
        runner.enqueue(data, goodAttributes);
        runner.enqueue(data, goodAttributes);
        runner.run();

        runner.assertTransferCount(PutHiveQL.REL_FAILURE, 1);
        runner.assertTransferCount(PutHiveQL.REL_SUCCESS, 3);
    }


    @Test
    public void testFailInMiddleWithBadParameterValue() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersonsAutoId);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");

        final Map<String, String> goodAttributes = new HashMap<>();
        goodAttributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        goodAttributes.put("hiveql.args.1.value", "84");

        final Map<String, String> badAttributes = new HashMap<>();
        badAttributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        badAttributes.put("hiveql.args.1.value", "9999");

        final byte[] data = "INSERT INTO PERSONS (NAME, CODE) VALUES ('Mark', ?)".getBytes();
        runner.enqueue(data, goodAttributes);
        runner.enqueue(data, badAttributes);
        runner.enqueue(data, goodAttributes);
        runner.enqueue(data, goodAttributes);
        runner.run();

        runner.assertTransferCount(PutHiveQL.REL_SUCCESS, 3);
        runner.assertTransferCount(PutHiveQL.REL_FAILURE, 1);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("Mark", rs.getString(2));
                assertEquals(84, rs.getInt(3));
                assertTrue(rs.next());
                assertTrue(rs.next());
                assertFalse(rs.next());
            }
        }
    }


    @Test
    public void testUsingSqlDataTypesWithNegativeValues() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE PERSONS (id integer primary key, name varchar(100), code bigint)");
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("hiveql.args.1.type", "-5");
        attributes.put("hiveql.args.1.value", "84");
        runner.enqueue("INSERT INTO PERSONS (ID, NAME, CODE) VALUES (1, 'Mark', ?)".getBytes(), attributes);
        runner.run();

        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_SUCCESS, 1);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("Mark", rs.getString(2));
                assertEquals(84, rs.getInt(3));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    public void testStatementsWithPreparedParameters() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersons);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.1.value", "1");

        attributes.put("hiveql.args.2.type", String.valueOf(Types.VARCHAR));
        attributes.put("hiveql.args.2.value", "Mark");

        attributes.put("hiveql.args.3.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.3.value", "84");

        runner.enqueue("INSERT INTO PERSONS (ID, NAME, CODE) VALUES (?, ?, ?)".getBytes(), attributes);
        runner.run();

        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_SUCCESS, 1);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("Mark", rs.getString(2));
                assertEquals(84, rs.getInt(3));
                assertFalse(rs.next());
            }
        }

        runner.clearTransferState();

        attributes.clear();
        attributes.put("hiveql.args.1.type", String.valueOf(Types.VARCHAR));
        attributes.put("hiveql.args.1.value", "George");

        attributes.put("hiveql.args.2.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.2.value", "1");

        runner.enqueue("UPDATE PERSONS SET NAME=? WHERE ID=?".getBytes(), attributes);
        runner.run();
        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_SUCCESS, 1);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("George", rs.getString(2));
                assertEquals(84, rs.getInt(3));
                assertFalse(rs.next());
            }
        }
    }


    @Test
    public void testMultipleStatementsWithinFlowFile() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersons);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");

        final String sql = "INSERT INTO PERSONS (ID, NAME, CODE) VALUES (?, ?, ?); " +
            "UPDATE PERSONS SET NAME='George' WHERE ID=?; ";
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.1.value", "1");

        attributes.put("hiveql.args.2.type", String.valueOf(Types.VARCHAR));
        attributes.put("hiveql.args.2.value", "Mark");

        attributes.put("hiveql.args.3.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.3.value", "84");

        attributes.put("hiveql.args.4.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.4.value", "1");

        runner.enqueue(sql.getBytes(), attributes);
        runner.run();

        // should fail because of the semicolon
        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_SUCCESS, 1);

        // Now we can check that the values were inserted by the multi-statement script.
        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals("Record ID mismatch", 1, rs.getInt(1));
                assertEquals("Record NAME mismatch", "George", rs.getString(2));
            }
        }
    }

    @Test
    public void testMultipleStatementsWithinFlowFilePlusEmbeddedDelimiter() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersons);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");

        final String sql = "INSERT INTO PERSONS (ID, NAME, CODE) VALUES (?, ?, ?); " +
                "UPDATE PERSONS SET NAME='George\\;' WHERE ID=?; ";
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.1.value", "1");

        attributes.put("hiveql.args.2.type", String.valueOf(Types.VARCHAR));
        attributes.put("hiveql.args.2.value", "Mark");

        attributes.put("hiveql.args.3.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.3.value", "84");

        attributes.put("hiveql.args.4.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.4.value", "1");

        runner.enqueue(sql.getBytes(), attributes);
        runner.run();

        // should fail because of the semicolon
        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_SUCCESS, 1);

        // Now we can check that the values were inserted by the multi-statement script.
        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals("Record ID mismatch", 1, rs.getInt(1));
                assertEquals("Record NAME mismatch", "George\\;", rs.getString(2));
            }
        }
    }


    @Test
    public void testWithNullParameter() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersons);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.1.value", "1");

        attributes.put("hiveql.args.2.type", String.valueOf(Types.VARCHAR));
        attributes.put("hiveql.args.2.value", "Mark");

        attributes.put("hiveql.args.3.type", String.valueOf(Types.INTEGER));

        runner.enqueue("INSERT INTO PERSONS (ID, NAME, CODE) VALUES (?, ?, ?)".getBytes(), attributes);
        runner.run();

        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_SUCCESS, 1);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals("Mark", rs.getString(2));
                assertEquals(0, rs.getInt(3));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    public void testInvalidStatement() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(createPersons);
            }
        }

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");

        final String sql = "INSERT INTO PERSONS (ID, NAME, CODE) VALUES (?, ?, ?); " +
            "UPDATE SOME_RANDOM_TABLE NAME='George' WHERE ID=?; ";
        final Map<String, String> attributes = new HashMap<>();
        attributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.1.value", "1");

        attributes.put("hiveql.args.2.type", String.valueOf(Types.VARCHAR));
        attributes.put("hiveql.args.2.value", "Mark");

        attributes.put("hiveql.args.3.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.3.value", "84");

        attributes.put("hiveql.args.4.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.4.value", "1");

        runner.enqueue(sql.getBytes(), attributes);
        runner.run();

        // should fail because of the table is invalid
        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_FAILURE, 1);

        try (final Connection conn = service.getConnection()) {
            try (final Statement stmt = conn.createStatement()) {
                final ResultSet rs = stmt.executeQuery("SELECT * FROM PERSONS");
                assertTrue(rs.next());
            }
        }
    }


    @Test
    public void testRetryableFailure() throws InitializationException, ProcessException, SQLException, IOException {
        final TestRunner runner = TestRunners.newTestRunner(PutHiveQL.class);
        final DBCPService service = new SQLExceptionService(null);
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);

        runner.setProperty(PutHiveQL.HIVE_DBCP_SERVICE, "dbcp");

        final String sql = "INSERT INTO PERSONS (ID, NAME, CODE) VALUES (?, ?, ?); " +
            "UPDATE PERSONS SET NAME='George' WHERE ID=?; ";

        final Map<String, String> attributes = new HashMap<>();
        attributes.put("hiveql.args.1.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.1.value", "1");

        attributes.put("hiveql.args.2.type", String.valueOf(Types.VARCHAR));
        attributes.put("hiveql.args.2.value", "Mark");

        attributes.put("hiveql.args.3.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.3.value", "84");

        attributes.put("hiveql.args.4.type", String.valueOf(Types.INTEGER));
        attributes.put("hiveql.args.4.value", "1");

        runner.enqueue(sql.getBytes(), attributes);
        runner.run();

        // should fail because there isn't a valid connection and tables don't exist.
        runner.assertAllFlowFilesTransferred(PutHiveQL.REL_RETRY, 1);
    }

    /**
     * Simple implementation only for testing purposes
     */
    private static class MockDBCPService extends AbstractControllerService implements HiveDBCPService {
        private final String dbLocation;

        MockDBCPService(final String dbLocation) {
            this.dbLocation = dbLocation;
        }

        @Override
        public String getIdentifier() {
            return "dbcp";
        }

        @Override
        public Connection getConnection() throws ProcessException {
            try {
                Class.forName("org.apache.derby.jdbc.EmbeddedDriver");
                return DriverManager.getConnection("jdbc:derby:" + dbLocation + ";create=true");
            } catch (final Exception e) {
                e.printStackTrace();
                throw new ProcessException("getConnection failed: " + e);
            }
        }

        @Override
        public String getConnectionURL() {
            return "jdbc:derby:" + dbLocation + ";create=true";
        }
    }

    /**
     * Simple implementation only for testing purposes
     */
    private static class SQLExceptionService extends AbstractControllerService implements HiveDBCPService {
        private final HiveDBCPService service;
        private int allowedBeforeFailure = 0;
        private int successful = 0;

        SQLExceptionService(final HiveDBCPService service) {
            this.service = service;
        }

        @Override
        public String getIdentifier() {
            return "dbcp";
        }

        @Override
        public Connection getConnection() throws ProcessException {
            try {
                if (++successful > allowedBeforeFailure) {
                    final Connection conn = Mockito.mock(Connection.class);
                    Mockito.when(conn.prepareStatement(Mockito.any(String.class))).thenThrow(new SQLException("Unit Test Generated SQLException"));
                    return conn;
                } else {
                    return service.getConnection();
                }
            } catch (final Exception e) {
                e.printStackTrace();
                throw new ProcessException("getConnection failed: " + e);
            }
        }

        @Override
        public String getConnectionURL() {
            return service.getConnectionURL();
        }
    }
}
