package com.iciql.dialect;

import com.iciql.SQLStatement;
import com.iciql.TableDefinition;
import com.iciql.TableDefinition.FieldDefinition;
import com.iciql.TableDefinition.IndexDefinition;
import com.iciql.util.StatementBuilder;

/**
 * H2 database dialect.
 */
public class H2Dialect extends DefaultSQLDialect {

	@Override
	public boolean supportsMemoryTables() {
		return true;
	}

	@Override
	public boolean supportsMerge() {
		return true;
	}

	@Override
	public String prepareCreateIndex(String schema, String table, IndexDefinition index) {
		StatementBuilder buff = new StatementBuilder();
		buff.append("CREATE ");
		switch (index.type) {
		case STANDARD:
			break;
		case UNIQUE:
			buff.append("UNIQUE ");
			break;
		case HASH:
			buff.append("HASH ");
			break;
		case UNIQUE_HASH:
			buff.append("UNIQUE HASH ");
			break;
		}
		buff.append("INDEX IF NOT EXISTS ");
		buff.append(index.indexName);
		buff.append(" ON ");
		buff.append(table);
		buff.append("(");
		for (String col : index.columnNames) {
			buff.appendExceptFirst(", ");
			buff.append(col);
		}
		buff.append(")");
		return buff.toString();
	}
	
	@Override
	public <T> void prepareMerge(SQLStatement stat, String schemaName, String tableName, TableDefinition<T> def, Object obj) {
		StatementBuilder buff = new StatementBuilder("MERGE INTO ");
		buff.append(prepareTableName(schemaName, tableName)).append(" (");
		buff.resetCount();
		for (FieldDefinition field : def.fields) {
			buff.appendExceptFirst(", ");
			buff.append(field.columnName);
		}
		buff.append(") KEY(");
		buff.resetCount();
		for (FieldDefinition field : def.fields) {
			if (field.isPrimaryKey) {
				buff.appendExceptFirst(", ");
				buff.append(field.columnName);
			}
		}
		buff.append(") ");
		buff.resetCount();
		buff.append("VALUES (");
		for (FieldDefinition field : def.fields) {
			buff.appendExceptFirst(", ");
			buff.append('?');
			Object value = def.getValue(obj, field);
			stat.addParameter(value);
		}
		buff.append(')');
		stat.setSQL(buff.toString());
	}
}