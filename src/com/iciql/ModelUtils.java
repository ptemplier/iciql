/*
 * Copyright 2004-2011 H2 Group.
 * Copyright 2011 James Moger.
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

import static com.iciql.util.StringUtils.isNullOrEmpty;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.iciql.TableDefinition.FieldDefinition;
import com.iciql.util.StringUtils;

/**
 * Utility methods for models related to type mapping, default value validation,
 * and class or field name creation.
 */
class ModelUtils {

	/**
	 * The list of supported data types. It is used by the runtime mapping for
	 * CREATE statements.
	 */
	private static final Map<Class<?>, String> SUPPORTED_TYPES = new HashMap<Class<?>, String>();

	static {
		Map<Class<?>, String> m = SUPPORTED_TYPES;
		m.put(String.class, "VARCHAR");
		m.put(Boolean.class, "BIT");
		m.put(Byte.class, "TINYINT");
		m.put(Short.class, "SMALLINT");
		m.put(Integer.class, "INT");
		m.put(Long.class, "BIGINT");
		m.put(Float.class, "REAL");
		m.put(Double.class, "DOUBLE");
		m.put(BigDecimal.class, "DECIMAL");
		m.put(java.sql.Timestamp.class, "TIMESTAMP");
		m.put(java.util.Date.class, "TIMESTAMP");
		m.put(java.sql.Date.class, "DATE");
		m.put(java.sql.Time.class, "TIME");
		m.put(byte[].class, "BLOB");
	}

	/**
	 * Convert SQL type aliases to the list of supported types. This map is used
	 * by generation and validation.
	 */
	private static final Map<String, String> SQL_TYPES = new HashMap<String, String>();

	static {
		Map<String, String> m = SQL_TYPES;
		m.put("CHAR", "VARCHAR");
		m.put("CHARACTER", "VARCHAR");
		m.put("NCHAR", "VARCHAR");
		m.put("VARCHAR_CASESENSITIVE", "VARCHAR");
		m.put("VARCHAR_IGNORECASE", "VARCHAR");
		m.put("LONGVARCHAR", "VARCHAR");
		m.put("VARCHAR2", "VARCHAR");
		m.put("NVARCHAR", "VARCHAR");
		m.put("NVARCHAR2", "VARCHAR");
		m.put("TEXT", "VARCHAR");
		m.put("NTEXT", "VARCHAR");
		m.put("TINYTEXT", "VARCHAR");
		m.put("MEDIUMTEXT", "VARCHAR");
		m.put("LONGTEXT", "VARCHAR");
		m.put("CLOB", "VARCHAR");
		m.put("NCLOB", "VARCHAR");

		// logic
		m.put("BOOL", "BIT");
		m.put("BOOLEAN", "BIT");

		// numeric
		m.put("BYTE", "TINYINT");
		m.put("INT2", "SMALLINT");
		m.put("YEAR", "SMALLINT");
		m.put("INTEGER", "INT");
		m.put("MEDIUMINT", "INT");
		m.put("INT4", "INT");
		m.put("SIGNED", "INT");
		m.put("INT8", "BIGINT");
		m.put("IDENTITY", "BIGINT");

		// decimal
		m.put("NUMBER", "DECIMAL");
		m.put("DEC", "DECIMAL");
		m.put("NUMERIC", "DECIMAL");
		m.put("FLOAT", "DOUBLE");
		m.put("FLOAT4", "DOUBLE");
		m.put("FLOAT8", "DOUBLE");

		// date
		m.put("DATETIME", "TIMESTAMP");
		m.put("SMALLDATETIME", "TIMESTAMP");

		// binary types
		m.put("TINYBLOB", "BLOB");
		m.put("MEDIUMBLOB", "BLOB");
		m.put("LONGBLOB", "BLOB");
		m.put("IMAGE", "BLOB");
		m.put("OID", "BLOB");
	}

	private static final List<String> KEYWORDS = Arrays.asList("abstract", "assert", "boolean", "break",
			"byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else",
			"enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import",
			"instanceof", "int", "interface", "long", "native", "new", "package", "private", "protected",
			"public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
			"throw", "throws", "transient", "try", "void", "volatile", "while", "false", "null", "true");

	/**
	 * Returns a SQL type mapping for a Java class.
	 * 
	 * @param fieldDef
	 *            the field to map
	 * @param strictTypeMapping
	 *            throws a IciqlException if type is unsupported
	 * @return
	 */
	static String getDataType(FieldDefinition fieldDef, boolean strictTypeMapping) {
		Class<?> fieldClass = fieldDef.field.getType();
		if (fieldClass.isEnum()) {
			if (fieldDef.enumType == null) {
				throw new IciqlException(fieldDef.field.getName() + " enum field does not specify @IQEnum!");
			}
			switch (fieldDef.enumType) {
			case STRING:
				if (fieldDef.maxLength <= 0) {
					return "TEXT";
				}
				return "VARCHAR";
			case ORDINAL:
			case ENUMID:
				return "INT";
			}
		}
		if (SUPPORTED_TYPES.containsKey(fieldClass)) {
			String type = SUPPORTED_TYPES.get(fieldClass);
			if (type.equals("VARCHAR") && fieldDef.maxLength <= 0) {
				// unspecified length strings are TEXT, not VARCHAR
				return "TEXT";
			}
			return type;
		}
		if (!strictTypeMapping) {
			return "VARCHAR";
		}
		throw new IciqlException("Unsupported type " + fieldClass.getName());
	}

	/**
	 * Returns the Java class for a given SQL type.
	 * 
	 * @param sqlType
	 * @param dateTimeClass
	 *            the preferred date class (java.util.Date or
	 *            java.sql.Timestamp)
	 * @return
	 */
	static Class<?> getClassForSqlType(String sqlType, Class<? extends java.util.Date> dateTimeClass) {
		sqlType = sqlType.toUpperCase();
		// XXX dropping "UNSIGNED" or parts like that could be trouble
		sqlType = sqlType.split(" ")[0].trim();

		if (SQL_TYPES.containsKey(sqlType)) {
			// convert the sqlType to a standard type
			sqlType = SQL_TYPES.get(sqlType);
		}
		Class<?> mappedClass = null;
		for (Class<?> clazz : SUPPORTED_TYPES.keySet()) {
			if (SUPPORTED_TYPES.get(clazz).equalsIgnoreCase(sqlType)) {
				mappedClass = clazz;
				break;
			}
		}
		if (mappedClass != null) {
			if (mappedClass.equals(java.util.Date.class) || mappedClass.equals(java.sql.Timestamp.class)) {
				return dateTimeClass;
			}
			return mappedClass;
		}
		return null;
	}

	/**
	 * Tries to create a convert a SQL table name to a camel case class name.
	 * 
	 * @param tableName
	 *            the SQL table name
	 * @return the class name
	 */
	static String convertTableToClassName(String tableName) {
		String[] chunks = StringUtils.arraySplit(tableName, '_', false);
		StringBuilder className = new StringBuilder();
		for (String chunk : chunks) {
			if (chunk.length() == 0) {
				// leading or trailing _
				continue;
			}
			className.append(Character.toUpperCase(chunk.charAt(0)));
			className.append(chunk.substring(1).toLowerCase());
		}
		return className.toString();
	}

	/**
	 * Ensures that SQL column names don't collide with Java keywords.
	 * 
	 * @param columnName
	 *            the column name
	 * @return the Java field name
	 */
	static String convertColumnToFieldName(String columnName) {
		String lower = columnName.toLowerCase();
		if (KEYWORDS.contains(lower)) {
			lower += "Value";
		}
		return lower;
	}

	/**
	 * Checks the formatting of IQColumn.defaultValue().
	 * 
	 * @param defaultValue
	 *            the default value
	 * @return true if it is
	 */
	static boolean isProperlyFormattedDefaultValue(String defaultValue) {
		if (isNullOrEmpty(defaultValue)) {
			return true;
		}
		Pattern literalDefault = Pattern.compile("'.*'");
		Pattern functionDefault = Pattern.compile("[^'].*[^']");
		return literalDefault.matcher(defaultValue).matches()
				|| functionDefault.matcher(defaultValue).matches();
	}

	/**
	 * Checks to see if the default value matches the class.
	 * 
	 * @param modelClass
	 *            the class
	 * @param defaultValue
	 *            the value
	 * @return true if it does
	 */
	static boolean isValidDefaultValue(Class<?> modelClass, String defaultValue) {

		if (defaultValue == null) {
			// NULL
			return true;
		}
		if (defaultValue.trim().length() == 0) {
			// NULL (effectively)
			return true;
		}

		// TODO H2 single-quotes literal values, which is useful.
		// MySQL does not single-quote literal values so its hard to
		// differentiate a FUNCTION/VARIABLE from a literal value.

		// function / variable
		Pattern functionDefault = Pattern.compile("[^'].*[^']");
		if (functionDefault.matcher(defaultValue).matches()) {
			// hard to validate this since its in the database
			// assume it is good
			return true;
		}

		// STRING
		if (modelClass == String.class) {
			Pattern stringDefault = Pattern.compile("'(.|\\n)*'");
			return stringDefault.matcher(defaultValue).matches();
		}

		String dateRegex = "[0-9]{1,4}[-/\\.][0-9]{1,2}[-/\\.][0-9]{1,2}";
		String timeRegex = "[0-2]{1}[0-9]{1}:[0-5]{1}[0-9]{1}:[0-5]{1}[0-9]{1}";

		// TIMESTAMP
		if (modelClass == java.util.Date.class || modelClass == java.sql.Timestamp.class) {
			// this may be a little loose....
			// 00-00-00 00:00:00
			// 00/00/00T00:00:00
			// 00.00.00T00:00:00
			Pattern pattern = Pattern.compile("'" + dateRegex + "." + timeRegex + "'");
			return pattern.matcher(defaultValue).matches();
		}

		// DATE
		if (modelClass == java.sql.Date.class) {
			// this may be a little loose....
			// 00-00-00
			// 00/00/00
			// 00.00.00
			Pattern pattern = Pattern.compile("'" + dateRegex + "'");
			return pattern.matcher(defaultValue).matches();
		}

		// TIME
		if (modelClass == java.sql.Time.class) {
			// 00:00:00
			Pattern pattern = Pattern.compile("'" + timeRegex + "'");
			return pattern.matcher(defaultValue).matches();
		}

		// NUMBER
		if (Number.class.isAssignableFrom(modelClass)) {
			// strip single quotes
			String unquoted = defaultValue;
			if (unquoted.charAt(0) == '\'') {
				unquoted = unquoted.substring(1);
			}
			if (unquoted.charAt(unquoted.length() - 1) == '\'') {
				unquoted = unquoted.substring(0, unquoted.length() - 1);
			}

			try {
				// delegate to static valueOf() method to parse string
				Method m = modelClass.getMethod("valueOf", String.class);
				m.invoke(null, unquoted);
			} catch (NumberFormatException ex) {
				return false;
			} catch (Throwable t) {
			}
		}
		return true;
	}
}
