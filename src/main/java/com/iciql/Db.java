/*
 * Copyright 2004-2011 H2 Group.
 * Copyright 2011 James Moger.
 * Copyright 2012 Frédéric Gaillard.
 * Copyright 2012 Alex Telepov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.iciql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.iciql.DbUpgrader.DefaultDbUpgrader;
import com.iciql.Iciql.IQTable;
import com.iciql.Iciql.IQVersion;
import com.iciql.Iciql.IQView;
import com.iciql.Iciql.Mode;
import com.iciql.util.IciqlLogger;
import com.iciql.util.JdbcUtils;
import com.iciql.util.StringUtils;
import com.iciql.util.Utils;
import com.iciql.util.WeakIdentityHashMap;

/**
 * This class represents a connection to a database.
 */

public class Db implements AutoCloseable {

	/**
	 * This map It holds unique tokens that are generated by functions such as
	 * Function.sum(..) in "db.from(p).select(Function.sum(p.unitPrice))". It
	 * doesn't actually hold column tokens, as those are bound to the query
	 * itself.
	 */
	private static final Map<Object, Token> TOKENS;

	private static final Map<String, Class<? extends SQLDialect>> DIALECTS;

	private final Connection conn;
	private final Mode mode;
	private final Map<Class<?>, TableDefinition<?>> classMap = Collections
			.synchronizedMap(new HashMap<Class<?>, TableDefinition<?>>());
	private final SQLDialect dialect;
	private DbUpgrader dbUpgrader = new DefaultDbUpgrader();
	private final Set<Class<?>> upgradeChecked = Collections.synchronizedSet(new HashSet<Class<?>>());

	private boolean skipCreate;
	private boolean autoSavePoint = true;
	private DaoStatementProvider daoStatementProvider;

	static {
		TOKENS = Collections.synchronizedMap(new WeakIdentityHashMap<Object, Token>());
		DIALECTS = Collections.synchronizedMap(new HashMap<String, Class<? extends SQLDialect>>());
		// can register by...
		// 1. Connection class name
		// 2. DatabaseMetaData.getDatabaseProductName()
		DIALECTS.put("Apache Derby", SQLDialectDerby.class);
		DIALECTS.put("H2", SQLDialectH2.class);
		DIALECTS.put("HSQL Database Engine", SQLDialectHSQL.class);
		DIALECTS.put("MySQL", SQLDialectMySQL.class);
		DIALECTS.put("PostgreSQL", SQLDialectPostgreSQL.class);
	    DIALECTS.put("Microsoft SQL Server", SQLDialectMSSQL.class);
	    DIALECTS.put("SQLite", SQLDialectSQLite.class);
	}

	private Db(Connection conn, Mode mode) {
		this.conn = conn;
		this.mode = mode;
		String databaseName = null;
		try {
			DatabaseMetaData data = conn.getMetaData();
			databaseName = data.getDatabaseProductName();
		} catch (SQLException s) {
			throw new IciqlException(s, "failed to retrieve database metadata!");
		}
		dialect = getDialect(databaseName, conn.getClass().getName());
		dialect.configureDialect(this);
		daoStatementProvider = new NoExternalDaoStatements();
	}

	/**
	 * Register a new/custom dialect class. You can use this method to replace
	 * any existing dialect or to add a new one.
	 *
	 * @param token
	 *            the fully qualified name of the connection class or the
	 *            expected result of DatabaseMetaData.getDatabaseProductName()
	 * @param dialectClass
	 *            the dialect class to register
	 */
	public static void registerDialect(String token, Class<? extends SQLDialect> dialectClass) {
		DIALECTS.put(token, dialectClass);
	}

	SQLDialect getDialect(String databaseName, String className) {
		Class<? extends SQLDialect> dialectClass = null;
		if (DIALECTS.containsKey(className)) {
			// dialect registered by connection class name
			dialectClass = DIALECTS.get(className);
		} else if (DIALECTS.containsKey(databaseName)) {
			// dialect registered by database name
			dialectClass = DIALECTS.get(databaseName);
		} else {
			// did not find a match, use default
			dialectClass = SQLDialectDefault.class;
		}
		return instance(dialectClass);
	}

	static <X> X registerToken(X x, Token token) {
		TOKENS.put(x, token);
		return x;
	}

	static Token getToken(Object x) {
		return TOKENS.get(x);
	}

	static <T> T instance(Class<T> clazz) {
		try {
			return clazz.newInstance();
		} catch (Exception e) {
			throw new IciqlException(e);
		}
	}

	public static Db open(String url) {
		return open(url, Mode.PROD);
	}

	public static Db open(String url, Mode mode) {
		try {
			Connection conn = JdbcUtils.getConnection(null, url, null, null);
			return new Db(conn, mode);
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
	}

	public static Db open(String url, String user, String password) {
		return open(url, user, password, Mode.PROD);
	}

	public static Db open(String url, String user, String password, Mode mode) {
		try {
			Connection conn = JdbcUtils.getConnection(null, url, user, password);
			return new Db(conn, mode);
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
	}

	public static Db open(String url, String user, char[] password) {
		return open(url, user, password, Mode.PROD);
	}

	public static Db open(String url, String user, char[] password, Mode mode) {
		try {
			Connection conn = JdbcUtils.getConnection(null, url, user, password == null ? null : new String(password));
			return new Db(conn, mode);
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
	}

	public static Db open(DataSource ds) {
		return open(ds, Mode.PROD);
	}

	/**
	 * Create a new database instance using a data source. This method is fast,
	 * so that you can always call open() / close() on usage.
	 *
	 * @param ds
	 *            the data source
	 * @param mode
	 *            the runtime mode
	 * @return the database instance.
	 */
	public static Db open(DataSource ds, Mode mode) {
		try {
			return new Db(ds.getConnection(), mode);
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
	}

	public static Db open(Connection conn) {
		return open(conn, Mode.PROD);
	}

	public static Db open(Connection conn, Mode mode) {
		return new Db(conn, mode);
	}

	/**
	 * Returns the Iciql runtime mode.
	 *
	 * @return the runtime mode
	 */
	public Mode getMode() {
		return mode;
	}

	/**
	 * Returns a new DAO instance for the specified class.
	 *
	 * @param daoClass
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("resource")
	public <X extends Dao> X open(Class<X> daoClass) {
		return new DaoProxy<X>(this, daoClass).build();
	}

	/**
	 * Returns the DAO statement provider.
	 *
	 * @return the DAO statement provider
	 */
	public DaoStatementProvider getDaoStatementProvider() {
		return daoStatementProvider;
	}

	/**
	 * Sets the DAO statement provider.
	 *
	 * @param statementProvider
	 */
	public void setDaoStatementProvider(DaoStatementProvider statementProvider) {
		if (statementProvider == null) {
			throw new IciqlException("You must provide a valid {0} instance!",
					DaoStatementProvider.class.getSimpleName());
		}

		this.daoStatementProvider = statementProvider;
	}

	/**
	 * Convenience function to avoid import statements in application code.
	 */
	public void activateConsoleLogger() {
		IciqlLogger.activateConsoleLogger();
	}

	/**
	 * Convenience function to avoid import statements in application code.
	 */
	public void deactivateConsoleLogger() {
		IciqlLogger.deactivateConsoleLogger();
	}

	public <T> boolean insert(T t) {
		Class<?> clazz = t.getClass();
		long rc = define(clazz).createIfRequired(this).insert(this, t, false);
		if (rc == 0) {
			throw new IciqlException("Failed to insert {0}.  Affected rowcount == 0.", t);
		}
		return rc == 1;
	}

	public <T> long insertAndGetKey(T t) {
		Class<?> clazz = t.getClass();
		return define(clazz).createIfRequired(this).insert(this, t, true);
	}

	/**
	 * Upsert INSERTS if the record does not exist or UPDATES the record if it
	 * does exist. Not all databases support MERGE and the syntax varies with
	 * the database.
	 *
	 * If the database does not support a MERGE or INSERT OR REPLACE INTO syntax
	 * the dialect can try to simulate a merge by implementing:
	 * <p>
	 * INSERT INTO foo... (SELECT ?,... FROM foo WHERE pk=? HAVING count(*)=0)
	 * <p>
	 * iciql will check the affected row count returned by the internal merge
	 * method and if the affected row count = 0, it will issue an update.
	 * <p>
	 * See the Derby dialect for an implementation of this technique.
	 * <p>
	 * If the dialect does not support merge an IciqlException will be thrown.
	 *
	 * @param t
	 */
	public <T> void upsert(T t) {
		Class<?> clazz = t.getClass();
		TableDefinition<?> def = define(clazz).createIfRequired(this);
		int rc = def.merge(this, t);
		if (rc == 0) {
			rc = def.update(this, t);
		}
		if (rc == 0) {
			throw new IciqlException("upsert failed");
		}
	}

	/**
	 * Merge INSERTS if the record does not exist or UPDATES the record if it
	 * does exist. Not all databases support MERGE and the syntax varies with
	 * the database.
	 *
	 * If the database does not support a MERGE or INSERT OR REPLACE INTO syntax
	 * the dialect can try to simulate a merge by implementing:
	 * <p>
	 * INSERT INTO foo... (SELECT ?,... FROM foo WHERE pk=? HAVING count(*)=0)
	 * <p>
	 * iciql will check the affected row count returned by the internal merge
	 * method and if the affected row count = 0, it will issue an update.
	 * <p>
	 * See the Derby dialect for an implementation of this technique.
	 * <p>
	 * If the dialect does not support merge an IciqlException will be thrown.
	 *
	 * @param t
	 */
	public <T> void merge(T t) {
		upsert(t);
	}

	public <T> boolean update(T t) {
		Class<?> clazz = t.getClass();
		return define(clazz).createIfRequired(this).update(this, t) == 1;
	}

	public <T> boolean delete(T t) {
		Class<?> clazz = t.getClass();
		return define(clazz).createIfRequired(this).delete(this, t) == 1;
	}

	public <T extends Object> Query<T> from(T alias) {
		Class<?> clazz = alias.getClass();
		define(clazz).createIfRequired(this);
		return Query.from(this, alias);
	}

	@SuppressWarnings("unchecked")
	public <T> boolean dropTable(Class<? extends T> modelClass) {
		TableDefinition<T> def = (TableDefinition<T>) define(modelClass);
		SQLStatement stat = new SQLStatement(this);
		getDialect().prepareDropTable(stat, def);
		IciqlLogger.drop(stat.getSQL());
		int rc = 0;
		try {
			rc = stat.executeUpdate();
		} catch (IciqlException e) {
			if (e.getIciqlCode() != IciqlException.CODE_OBJECT_NOT_FOUND) {
				throw e;
			}
		}
		// remove this model class from the table definition cache
		classMap.remove(modelClass);
		// remove this model class from the upgrade checked cache
		upgradeChecked.remove(modelClass);
		return rc == 1;
	}

	@SuppressWarnings("unchecked")
	public <T> boolean dropView(Class<? extends T> modelClass) {
		TableDefinition<T> def = (TableDefinition<T>) define(modelClass);
		SQLStatement stat = new SQLStatement(this);
		getDialect().prepareDropView(stat, def);
		IciqlLogger.drop(stat.getSQL());
		int rc = 0;
		try {
			rc = stat.executeUpdate();
		} catch (IciqlException e) {
			if (e.getIciqlCode() != IciqlException.CODE_OBJECT_NOT_FOUND) {
				throw e;
			}
		}
		// remove this model class from the table definition cache
		classMap.remove(modelClass);
		// remove this model class from the upgrade checked cache
		upgradeChecked.remove(modelClass);
		return rc == 1;
	}

	public <T> List<T> buildObjects(Class<? extends T> modelClass, ResultSet rs) {
		return buildObjects(modelClass, false, rs);
	}

	@SuppressWarnings("unchecked")
	public <T> List<T> buildObjects(Class<? extends T> modelClass, boolean wildcardSelect, ResultSet rs) {
		List<T> result = new ArrayList<T>();
		TableDefinition<T> def = (TableDefinition<T>) define(modelClass);
		try {
			int[] columns = def.mapColumns(wildcardSelect, rs);
			while (rs.next()) {
				T item = Utils.newObject(modelClass);
				def.readRow(dialect, item, rs, columns);
				result.add(item);
			}
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
		return result;
	}

	Db upgradeDb() {
		if (!upgradeChecked.contains(dbUpgrader.getClass())) {
			// flag as checked immediately because calls are nested.
			upgradeChecked.add(dbUpgrader.getClass());

			IQVersion model = dbUpgrader.getClass().getAnnotation(IQVersion.class);
			if (model.value() == 0) {
				// try superclass
				Class<?> superClass = dbUpgrader.getClass().getSuperclass();
				if (superClass.isAnnotationPresent(IQVersion.class)) {
					model = superClass.getAnnotation(IQVersion.class);
				}
			}
			if (model.value() > 0) {
				DbVersion v = new DbVersion();
				// (SCHEMA="" && TABLE="") == DATABASE
				DbVersion dbVersion = from(v).where(v.schemaName).is("").and(v.tableName).is("")
						.selectFirst();
				if (dbVersion == null) {
					// database has no version registration, but model specifies
					// version: insert DbVersion entry and return.
					DbVersion newDb = new DbVersion(model.value());
					// database is an older version than the model
					boolean success = dbUpgrader.upgradeDatabase(this, 0, newDb.version);
					if (success) {
						insert(newDb);
					}
				} else {
					// database has a version registration:
					// check to see if upgrade is required.
					if ((model.value() > dbVersion.version) && (dbUpgrader != null)) {
						// database is an older version than the model
						boolean success = dbUpgrader.upgradeDatabase(this, dbVersion.version, model.value());
						if (success) {
							dbVersion.version = model.value();
							update(dbVersion);
						}
					}
				}
			}
		}
		return this;
	}

	<T> void upgradeTable(TableDefinition<T> model) {
		if (!upgradeChecked.contains(model.getModelClass())) {
			// flag is checked immediately because calls are nested
			upgradeChecked.add(model.getModelClass());

			if (model.tableVersion > 0) {
				// table is using iciql version tracking.
				DbVersion v = new DbVersion();
				String schema = StringUtils.isNullOrEmpty(model.schemaName) ? "" : model.schemaName;
				DbVersion dbVersion = from(v).where(v.schemaName).is(schema).and(v.tableName)
						.is(model.tableName).selectFirst();
				if (dbVersion == null) {
					// table has no version registration, but model specifies
					// version: insert DbVersion entry
					DbVersion newTable = new DbVersion(model.tableVersion);
					newTable.schemaName = schema;
					newTable.tableName = model.tableName;
					insert(newTable);
				} else {
					// table has a version registration:
					// check if upgrade is required
					if ((model.tableVersion > dbVersion.version) && (dbUpgrader != null)) {
						// table is an older version than model
						boolean success = dbUpgrader.upgradeTable(this, schema, model.tableName,
								dbVersion.version, model.tableVersion);
						if (success) {
							dbVersion.version = model.tableVersion;
							update(dbVersion);
						}
					}
				}
			}
		}
	}

	<T> TableDefinition<T> define(Class<T> clazz) {
		TableDefinition<T> def = getTableDefinition(clazz);
		if (def == null) {
			upgradeDb();
			def = new TableDefinition<T>(clazz);
			def.mapFields(this);
			classMap.put(clazz, def);
			if (Iciql.class.isAssignableFrom(clazz)) {
				T t = instance(clazz);
				Iciql table = (Iciql) t;
				Define.define(def, table);
			} else if (clazz.isAnnotationPresent(IQTable.class)) {
				// annotated classes skip the Define().define() static
				// initializer
				T t = instance(clazz);
				def.mapObject(t);
			} else if (clazz.isAnnotationPresent(IQView.class)) {
				// annotated classes skip the Define().define() static
				// initializer
				T t = instance(clazz);
				def.mapObject(t);
			}
		}
		return def;
	}

	<T> boolean hasCreated(Class<T> clazz) {
		return upgradeChecked.contains(clazz);
	}

	public synchronized void setDbUpgrader(DbUpgrader upgrader) {
		if (!upgrader.getClass().isAnnotationPresent(IQVersion.class)) {
			throw new IciqlException("DbUpgrader must be annotated with " + IQVersion.class.getSimpleName());
		}
		this.dbUpgrader = upgrader;
		upgradeChecked.clear();
	}

	public SQLDialect getDialect() {
		return dialect;
	}

	public Connection getConnection() {
		return conn;
	}

	@Override
	public void close() {
		try {
			conn.close();
		} catch (Exception e) {
			throw new IciqlException(e);
		}
	}

	public <A> TestCondition<A> test(A x) {
		return new TestCondition<A>(x);
	}

	public <T> void insertAll(List<T> list) {
		if (list.size() == 0) {
			return;
		}
		Savepoint savepoint = null;
		try {
			Class<?> clazz = list.get(0).getClass();
			TableDefinition<?> def = define(clazz).createIfRequired(this);
			savepoint = prepareSavepoint();
			for (T t : list) {
				PreparedStatement ps = def.createInsertStatement(this, t, false);
				int rc = ps.executeUpdate();
				if (rc == 0) {
					throw new IciqlException("Failed to insert {0}.  Affected rowcount == 0.", t);
				}
			}
			commit(savepoint);
		} catch (SQLException e) {
			rollback(savepoint);
			throw new IciqlException(e);
		} catch (IciqlException e) {
			rollback(savepoint);
			throw e;
		}
	}

	public <T> List<Long> insertAllAndGetKeys(List<T> list) {
		List<Long> identities = new ArrayList<Long>();
		if (list.size() == 0) {
			return identities;
		}
		Savepoint savepoint = null;
		try {
			Class<?> clazz = list.get(0).getClass();
			TableDefinition<?> def = define(clazz).createIfRequired(this);
			savepoint = prepareSavepoint();
			for (T t : list) {
				long key = def.insert(this,  t, true);
				identities.add(key);
			}
			commit(savepoint);
		} catch (IciqlException e) {
			rollback(savepoint);
			throw e;
		}
		return identities;
	}

	public <T> void updateAll(List<T> list) {
		if (list.size() == 0) {
			return;
		}
		Savepoint savepoint = null;
		try {
			Class<?> clazz = list.get(0).getClass();
			TableDefinition<?> def = define(clazz).createIfRequired(this);
			savepoint = prepareSavepoint();
			for (T t : list) {
				def.update(this, t);
			}
			commit(savepoint);
		} catch (IciqlException e) {
			rollback(savepoint);
			throw e;
		}
	}

	public <T> void deleteAll(List<T> list) {
		if (list.size() == 0) {
			return;
		}
		Savepoint savepoint = null;
		try {
			Class<?> clazz = list.get(0).getClass();
			TableDefinition<?> def = define(clazz).createIfRequired(this);
			savepoint = prepareSavepoint();
			for (T t : list) {
				def.delete(this,  t);
			}
			commit(savepoint);
		} catch (IciqlException e) {
			rollback(savepoint);
			throw e;
		}
	}

	PreparedStatement prepare(String sql, boolean returnGeneratedKeys) {
		IciqlException.checkUnmappedField(sql);
		try {
			if (returnGeneratedKeys) {
				return conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
			}
			return conn.prepareStatement(sql);
		} catch (SQLException e) {
			throw IciqlException.fromSQL(sql, e);
		}
	}

	Savepoint prepareSavepoint() {
		// don't change auto-commit mode.
		// don't create save point.
		if (!autoSavePoint || !dialect.supportsSavePoints()) {
			return null;
		}
		// create a savepoint
		Savepoint savepoint = null;
		try {
			conn.setAutoCommit(false);
			savepoint = conn.setSavepoint();
		} catch (SQLFeatureNotSupportedException e) {
			// jdbc driver does not support save points
		} catch (SQLException e) {
			throw new IciqlException(e, "Could not create save point");
		}
		return savepoint;
	}

	void commit(Savepoint savepoint) {
		if (savepoint != null) {
			try {
				conn.commit();
				conn.setAutoCommit(true);
			} catch (SQLException e) {
				throw new IciqlException(e, "Failed to commit pending transactions");
			}
		}
	}

	void rollback(Savepoint savepoint) {
		if (savepoint != null) {
			try {
				conn.rollback(savepoint);
				conn.setAutoCommit(true);
			} catch (SQLException s) {
				throw new IciqlException(s, "Failed to rollback transactions");
			}
		}
	}

	@SuppressWarnings("unchecked")
	<T> TableDefinition<T> getTableDefinition(Class<T> clazz) {
		return (TableDefinition<T>) classMap.get(clazz);
	}

	/**
	 * Run a SQL query directly against the database.
	 *
	 * Be sure to close the ResultSet with
	 *
	 * <pre>
	 * JdbcUtils.closeSilently(rs, true);
	 * </pre>
	 *
	 * @param sql
	 *            the SQL statement
	 * @param args
	 *            optional object arguments for x=? tokens in query
	 * @return the result set
	 */
	public ResultSet executeQuery(String sql, List<?> args) {
		return executeQuery(sql, args.toArray());
	}

	/**
	 * Run a SQL query directly against the database.
	 *
	 * Be sure to close the ResultSet with
	 *
	 * <pre>
	 * JdbcUtils.closeSilently(rs, true);
	 * </pre>
	 *
	 * @param sql
	 *            the SQL statement
	 * @param args
	 *            optional object arguments for x=? tokens in query
	 * @return the result set
	 */
	public ResultSet executeQuery(String sql, Object... args) {
		try {
			if (args == null || args.length == 0) {
				return conn.createStatement().executeQuery(sql);
			} else {
				PreparedStatement stat = conn.prepareStatement(sql);
				int i = 1;
				for (Object arg : args) {
					stat.setObject(i++, arg);
				}
				return stat.executeQuery();
			}
		} catch (SQLException e) {
			throw new IciqlException(e);
		}
	}

	/**
	 * Run a SQL query directly against the database and map the results to the
	 * model class.
	 *
	 * @param modelClass
	 *            the model class to bind the query ResultSet rows into.
	 * @param sql
	 *            the SQL statement
	 * @return the result set
	 */
	public <T> List<T> executeQuery(Class<? extends T> modelClass, String sql, List<?> args) {
		return executeQuery(modelClass, sql, args.toArray());
	}

	/**
	 * Run a SQL query directly against the database and map the results to the
	 * model class.
	 *
	 * @param modelClass
	 *            the model class to bind the query ResultSet rows into.
	 * @param sql
	 *            the SQL statement
	 * @return the result set
	 */
	public <T> List<T> executeQuery(Class<? extends T> modelClass, String sql, Object... args) {
		ResultSet rs = null;
		try {
			if (args == null || args.length == 0) {
				rs = conn.createStatement().executeQuery(sql);
			} else {
				PreparedStatement stat = conn.prepareStatement(sql);
				int i = 1;
				for (Object arg : args) {
					stat.setObject(i++, arg);
				}
				rs = stat.executeQuery();
			}
			boolean wildcardSelect = sql.toLowerCase().startsWith("select *")
					|| sql.toLowerCase().startsWith("select distinct *");
			return buildObjects(modelClass, wildcardSelect, rs);
		} catch (SQLException e) {
			throw new IciqlException(e);
		} finally {
			JdbcUtils.closeSilently(rs, true);
		}
	}

	/**
	 * Run a SQL statement directly against the database.
	 *
	 * @param sql
	 *            the SQL statement
	 * @return the update count
	 */
	public int executeUpdate(String sql, Object... args) {
		Statement stat = null;
		try {
			int updateCount;
			if (args == null || args.length == 0) {
				stat = conn.createStatement();
				updateCount = stat.executeUpdate(sql);
			} else {
				PreparedStatement ps = conn.prepareStatement(sql);
				int i = 1;
				for (Object arg : args) {
					ps.setObject(i++, arg);
				}
				updateCount = ps.executeUpdate();
				stat = ps;
			}
			return updateCount;
		} catch (SQLException e) {
			throw new IciqlException(e);
		} finally {
			JdbcUtils.closeSilently(stat);
		}
	}

	/**
	 * Allow to enable/disable globally createIfRequired in TableDefinition.
	 * For advanced user wanting to gain full control of transactions.
	 * Default value is false.
	 * @param skipCreate
	 */
	public void setSkipCreate(boolean skipCreate) {
		this.skipCreate = skipCreate;
	}

	public boolean getSkipCreate() {
		return this.skipCreate;
	}

	/**
	 * Allow to enable/disable usage of save point.
	 * For advanced user wanting to gain full control of transactions.
	 * Default value is false.
	 * @param autoSavePoint
	 */
	public void setAutoSavePoint(boolean autoSavePoint) {
		this.autoSavePoint = autoSavePoint;
	}

	public boolean getAutoSavePoint() {
		return this.autoSavePoint;
	}

	/**
	 * Default DAO statement provider.
	 */
	class NoExternalDaoStatements implements DaoStatementProvider {

		@Override
		public String getStatement(String idOrStatement, Mode mode) {
			return idOrStatement;
		}

	}
}
