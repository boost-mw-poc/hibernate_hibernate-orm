/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.community.dialect;

import jakarta.persistence.TemporalType;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.QueryTimeoutException;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.Dialect;
import org.hibernate.dialect.SybaseDriverKind;
import org.hibernate.dialect.aggregate.AggregateSupport;
import org.hibernate.dialect.aggregate.SybaseASEAggregateSupport;
import org.hibernate.dialect.function.CommonFunctionFactory;
import org.hibernate.dialect.lock.internal.TransactSQLLockingSupport;
import org.hibernate.dialect.lock.spi.LockingSupport;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.dialect.pagination.TopLimitHandler;
import org.hibernate.engine.jdbc.Size;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockTimeoutException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;
import org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor;
import org.hibernate.exception.spi.ViolatedConstraintNameExtractor;
import org.hibernate.query.common.TemporalUnit;
import org.hibernate.query.sqm.IntervalType;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.JdbcType;
import org.hibernate.type.descriptor.jdbc.TimestampJdbcType;
import org.hibernate.type.descriptor.jdbc.TinyIntJdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.descriptor.sql.internal.CapacityDependentDdlType;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.hibernate.Timeouts.SKIP_LOCKED_MILLI;
import static org.hibernate.exception.spi.TemplatedViolatedConstraintNameExtractor.extractUsingTemplate;
import static org.hibernate.internal.util.JdbcExceptionHelper.extractErrorCode;
import static org.hibernate.internal.util.JdbcExceptionHelper.extractSqlState;
import static org.hibernate.type.SqlTypes.BIGINT;
import static org.hibernate.type.SqlTypes.BOOLEAN;
import static org.hibernate.type.SqlTypes.DATE;
import static org.hibernate.type.SqlTypes.TIME;
import static org.hibernate.type.SqlTypes.TIMESTAMP;
import static org.hibernate.type.SqlTypes.TIMESTAMP_WITH_TIMEZONE;
import static org.hibernate.type.SqlTypes.XML_ARRAY;

/**
 * A {@linkplain Dialect SQL dialect} for Sybase Adaptive Server Enterprise 11.9 and above.
 */
public class SybaseASELegacyDialect extends SybaseLegacyDialect {

	private final SizeStrategy sizeStrategy = new SizeStrategyImpl() {
		@Override
		public Size resolveSize(
				JdbcType jdbcType,
				JavaType<?> javaType,
				Integer precision,
				Integer scale,
				Long length) {
			switch ( jdbcType.getDefaultSqlTypeCode() ) {
				case Types.FLOAT:
					// Sybase ASE allows FLOAT with a precision up to 48
					if ( precision != null ) {
						return Size.precision( Math.min( Math.max( precision, 1 ), 48 ) );
					}
			}
			return super.resolveSize( jdbcType, javaType, precision, scale, length );
		}
	};

	private final boolean ansiNull;

	public SybaseASELegacyDialect() {
		this( DatabaseVersion.make( 11 ) );
	}

	public SybaseASELegacyDialect(DatabaseVersion version) {
		super(version);
		ansiNull = false;
	}

	public SybaseASELegacyDialect(DialectResolutionInfo info) {
		super(info);
		ansiNull = isAnsiNull( info.getDatabaseMetadata() );
	}

	@Override
	protected String columnType(int sqlTypeCode) {
		switch ( sqlTypeCode ) {
			case BOOLEAN:
				// On Sybase ASE, the 'bit' type cannot be null,
				// and cannot have indexes (while we don't use
				// tinyint to store signed bytes, we can use it
				// to store boolean values)
				return "tinyint";
			case BIGINT:
				// Sybase ASE didn't introduce 'bigint' until version 15.0
				return getVersion().isBefore( 15 ) ? "numeric(19,0)" : super.columnType( sqlTypeCode );
			case DATE:
				return getVersion().isSameOrAfter( 12 ) ? "date" : super.columnType( sqlTypeCode );
			case TIME:
				return getVersion().isSameOrAfter( 12 ) ? "time" : super.columnType( sqlTypeCode );
			default:
				return super.columnType( sqlTypeCode );
		}
	}

	@Override
	protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.registerColumnTypes( typeContributions, serviceRegistry );
		final DdlTypeRegistry ddlTypeRegistry = typeContributions.getTypeConfiguration().getDdlTypeRegistry();

		// According to Wikipedia bigdatetime and bigtime were added in 15.5
		// But with jTDS we can't use them as the driver can't handle the types
		if ( getVersion().isSameOrAfter( 15, 5 ) && getDriverKind() != SybaseDriverKind.JTDS ) {
			ddlTypeRegistry.addDescriptor(
					CapacityDependentDdlType.builder( TIME, "bigtime", "bigtime", this )
							.withTypeCapacity( 3, "time" )
							.build()
			);
			ddlTypeRegistry.addDescriptor(
					CapacityDependentDdlType.builder( TIMESTAMP, "bigdatetime", "bigdatetime", this )
							.withTypeCapacity( 3, "datetime" )
							.build()
			);
			ddlTypeRegistry.addDescriptor(
					CapacityDependentDdlType.builder( TIMESTAMP_WITH_TIMEZONE, "bigdatetime", "bigdatetime", this )
							.withTypeCapacity( 3, "datetime" )
							.build()
			);
		}
	}

	@Override
	public int getPreferredSqlTypeCodeForArray() {
		return XML_ARRAY;
	}

	@Override
	public int getMaxVarcharLength() {
		// the maximum length of a VARCHAR or VARBINARY
		// column depends on the page size and ASE version
		// and is actually a limit on the whole row length,
		// not the individual column length -- anyway, the
		// largest possible page size is 16k, so that's a
		// hard upper limit
		return 16_384;
	}

	@Override
	public void initializeFunctionRegistry(FunctionContributions functionContributions) {
		super.initializeFunctionRegistry( functionContributions );

		CommonFunctionFactory functionFactory = new CommonFunctionFactory( functionContributions);

		functionFactory.unnest_sybasease();
		functionFactory.generateSeries_sybasease( getMaximumSeriesSize() );
		functionFactory.xmltable_sybasease();
	}

	/**
	 * Sybase ASE doesn't support the {@code generate_series} function or {@code lateral} recursive CTEs,
	 * so it has to be emulated with the {@code xmltable} and {@code replicate} functions.
	 */
	protected int getMaximumSeriesSize() {
		// The maximum possible value for replicating an XML tag, so that the resulting string stays below the 16K limit
		// https://infocenter.sybase.com/help/index.jsp?topic=/com.sybase.infocenter.dc32300.1570/html/sqlug/sqlug31.htm
		return 4094;
	}

	private static boolean isAnsiNull(DatabaseMetaData databaseMetaData) {
		if ( databaseMetaData != null ) {
			try (java.sql.Statement s = databaseMetaData.getConnection().createStatement() ) {
				final ResultSet rs = s.executeQuery( "SELECT @@options" );
				if ( rs.next() ) {
					final byte[] optionBytes = rs.getBytes( 1 );
					// By trial and error, enabling and disabling ansinull revealed that this bit is the indicator
					return ( optionBytes[4] & 2 ) == 2;
				}
			}
			catch (SQLException ex) {
				// Ignore
			}
		}
		return false;
	}

	public boolean isAnsiNullOn() {
		return ansiNull;
	}

	@Override
	public int getFloatPrecision() {
		return 15;
	}

	@Override
	public int getDoublePrecision() {
		return 48;
	}

	@Override
	public SizeStrategy getSizeStrategy() {
		return sizeStrategy;
	}

	@Override
	public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
		return new StandardSqlAstTranslatorFactory() {
			@Override
			protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(
					SessionFactoryImplementor sessionFactory, Statement statement) {
				return new SybaseASELegacySqlAstTranslator<>( sessionFactory, statement );
			}
		};
	}

	@Override
	public AggregateSupport getAggregateSupport() {
		return SybaseASEAggregateSupport.valueOf( this );
	}

	/**
	 * The Sybase ASE {@code BIT} type does not allow
	 * null values, so we don't use it.
	 *
	 * @return false
	 */
	@Override
	public boolean supportsBitType() {
		return false;
	}

	@Override
	public boolean supportsDistinctFromPredicate() {
		return getVersion().isSameOrAfter( 16, 3 );
	}

	@Override
	public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
		super.contributeTypes( typeContributions, serviceRegistry );

		final JdbcTypeRegistry jdbcTypeRegistry = typeContributions.getTypeConfiguration()
				.getJdbcTypeRegistry();
		jdbcTypeRegistry.addDescriptor( Types.BOOLEAN, TinyIntJdbcType.INSTANCE );
		jdbcTypeRegistry.addDescriptor( Types.TIMESTAMP_WITH_TIMEZONE, TimestampJdbcType.INSTANCE );
	}

	@Override
	public String currentDate() {
		return "current_date()";
	}

	@Override
	public String currentTime() {
		return "current_time()";
	}

	@Override
	public String currentTimestamp() {
		return "current_bigdatetime()";
	}

	@Override
	public long getFractionalSecondPrecisionInNanos() {
		// Sybase supports microsecond precision
		// but when we use it we just get numerical
		// overflows from timestamp arithmetic
		return 1_000_000;
	}

	@Override
	public String timestampaddPattern(TemporalUnit unit, TemporalType temporalType, IntervalType intervalType) {
		switch ( unit ) {
			case NANOSECOND:
				return "dateadd(ms,?2/1000000,?3)";
//				return "dateadd(mcs,?2/1000,?3)";
			case NATIVE:
				return "dateadd(ms,?2,?3)";
//				return "dateadd(mcs,?2,?3)";
			default:
				return "dateadd(?1,?2,?3)";
		}
	}

	@Override
	public String timestampdiffPattern(TemporalUnit unit, TemporalType fromTemporalType, TemporalType toTemporalType) {
		switch ( unit ) {
			case NANOSECOND:
				return "(cast(datediff(ms,?2,?3) as numeric(21))*1000000)";
//				return "(cast(datediff(mcs,?2,?3) as numeric(21))*1000)";
//				}
			case NATIVE:
				return "cast(datediff(ms,?2,?3) as numeric(21))";
//				return "cast(datediff(mcs,cast(?2 as bigdatetime),cast(?3 as bigdatetime)) as numeric(21))";
			default:
				return "datediff(?1,?2,?3)";
		}
	}


	@Override
	protected void registerDefaultKeywords() {
		super.registerDefaultKeywords();
		registerKeyword( "add" );
		registerKeyword( "all" );
		registerKeyword( "alter" );
		registerKeyword( "and" );
		registerKeyword( "any" );
		registerKeyword( "arith_overflow" );
		registerKeyword( "as" );
		registerKeyword( "asc" );
		registerKeyword( "at" );
		registerKeyword( "authorization" );
		registerKeyword( "avg" );
		registerKeyword( "begin" );
		registerKeyword( "between" );
		registerKeyword( "break" );
		registerKeyword( "browse" );
		registerKeyword( "bulk" );
		registerKeyword( "by" );
		registerKeyword( "cascade" );
		registerKeyword( "case" );
		registerKeyword( "char_convert" );
		registerKeyword( "check" );
		registerKeyword( "checkpoint" );
		registerKeyword( "close" );
		registerKeyword( "clustered" );
		registerKeyword( "coalesce" );
		registerKeyword( "commit" );
		registerKeyword( "compute" );
		registerKeyword( "confirm" );
		registerKeyword( "connect" );
		registerKeyword( "constraint" );
		registerKeyword( "continue" );
		registerKeyword( "controlrow" );
		registerKeyword( "convert" );
		registerKeyword( "count" );
		registerKeyword( "count_big" );
		registerKeyword( "create" );
		registerKeyword( "current" );
		registerKeyword( "cursor" );
		registerKeyword( "database" );
		registerKeyword( "dbcc" );
		registerKeyword( "deallocate" );
		registerKeyword( "declare" );
		registerKeyword( "decrypt" );
		registerKeyword( "default" );
		registerKeyword( "delete" );
		registerKeyword( "desc" );
		registerKeyword( "determnistic" );
		registerKeyword( "disk" );
		registerKeyword( "distinct" );
		registerKeyword( "drop" );
		registerKeyword( "dummy" );
		registerKeyword( "dump" );
		registerKeyword( "else" );
		registerKeyword( "encrypt" );
		registerKeyword( "end" );
		registerKeyword( "endtran" );
		registerKeyword( "errlvl" );
		registerKeyword( "errordata" );
		registerKeyword( "errorexit" );
		registerKeyword( "escape" );
		registerKeyword( "except" );
		registerKeyword( "exclusive" );
		registerKeyword( "exec" );
		registerKeyword( "execute" );
		registerKeyword( "exist" );
		registerKeyword( "exit" );
		registerKeyword( "exp_row_size" );
		registerKeyword( "external" );
		registerKeyword( "fetch" );
		registerKeyword( "fillfactor" );
		registerKeyword( "for" );
		registerKeyword( "foreign" );
		registerKeyword( "from" );
		registerKeyword( "goto" );
		registerKeyword( "grant" );
		registerKeyword( "group" );
		registerKeyword( "having" );
		registerKeyword( "holdlock" );
		registerKeyword( "identity" );
		registerKeyword( "identity_gap" );
		registerKeyword( "identity_start" );
		registerKeyword( "if" );
		registerKeyword( "in" );
		registerKeyword( "index" );
		registerKeyword( "inout" );
		registerKeyword( "insensitive" );
		registerKeyword( "insert" );
		registerKeyword( "install" );
		registerKeyword( "intersect" );
		registerKeyword( "into" );
		registerKeyword( "is" );
		registerKeyword( "isolation" );
		registerKeyword( "jar" );
		registerKeyword( "join" );
		registerKeyword( "key" );
		registerKeyword( "kill" );
		registerKeyword( "level" );
		registerKeyword( "like" );
		registerKeyword( "lineno" );
		registerKeyword( "load" );
		registerKeyword( "lock" );
		registerKeyword( "materialized" );
		registerKeyword( "max" );
		registerKeyword( "max_rows_per_page" );
		registerKeyword( "min" );
		registerKeyword( "mirror" );
		registerKeyword( "mirrorexit" );
		registerKeyword( "modify" );
		registerKeyword( "national" );
		registerKeyword( "new" );
		registerKeyword( "noholdlock" );
		registerKeyword( "nonclustered" );
		registerKeyword( "nonscrollable" );
		registerKeyword( "non_sensitive" );
		registerKeyword( "not" );
		registerKeyword( "null" );
		registerKeyword( "nullif" );
		registerKeyword( "numeric_truncation" );
		registerKeyword( "of" );
		registerKeyword( "off" );
		registerKeyword( "offsets" );
		registerKeyword( "on" );
		registerKeyword( "once" );
		registerKeyword( "online" );
		registerKeyword( "only" );
		registerKeyword( "open" );
		registerKeyword( "option" );
		registerKeyword( "or" );
		registerKeyword( "order" );
		registerKeyword( "out" );
		registerKeyword( "output" );
		registerKeyword( "over" );
		registerKeyword( "artition" );
		registerKeyword( "perm" );
		registerKeyword( "permanent" );
		registerKeyword( "plan" );
		registerKeyword( "prepare" );
		registerKeyword( "primary" );
		registerKeyword( "print" );
		registerKeyword( "privileges" );
		registerKeyword( "proc" );
		registerKeyword( "procedure" );
		registerKeyword( "processexit" );
		registerKeyword( "proxy_table" );
		registerKeyword( "public" );
		registerKeyword( "quiesce" );
		registerKeyword( "raiserror" );
		registerKeyword( "read" );
		registerKeyword( "readpast" );
		registerKeyword( "readtext" );
		registerKeyword( "reconfigure" );
		registerKeyword( "references" );
		registerKeyword( "remove" );
		registerKeyword( "reorg" );
		registerKeyword( "replace" );
		registerKeyword( "replication" );
		registerKeyword( "reservepagegap" );
		registerKeyword( "return" );
		registerKeyword( "returns" );
		registerKeyword( "revoke" );
		registerKeyword( "role" );
		registerKeyword( "rollback" );
		registerKeyword( "rowcount" );
		registerKeyword( "rows" );
		registerKeyword( "rule" );
		registerKeyword( "save" );
		registerKeyword( "schema" );
		registerKeyword( "scroll" );
		registerKeyword( "scrollable" );
		registerKeyword( "select" );
		registerKeyword( "semi_sensitive" );
		registerKeyword( "set" );
		registerKeyword( "setuser" );
		registerKeyword( "shared" );
		registerKeyword( "shutdown" );
		registerKeyword( "some" );
		registerKeyword( "statistics" );
		registerKeyword( "stringsize" );
		registerKeyword( "stripe" );
		registerKeyword( "sum" );
		registerKeyword( "syb_identity" );
		registerKeyword( "syb_restree" );
		registerKeyword( "syb_terminate" );
		registerKeyword( "top" );
		registerKeyword( "table" );
		registerKeyword( "temp" );
		registerKeyword( "temporary" );
		registerKeyword( "textsize" );
		registerKeyword( "to" );
		registerKeyword( "tracefile" );
		registerKeyword( "tran" );
		registerKeyword( "transaction" );
		registerKeyword( "trigger" );
		registerKeyword( "truncate" );
		registerKeyword( "tsequal" );
		registerKeyword( "union" );
		registerKeyword( "unique" );
		registerKeyword( "unpartition" );
		registerKeyword( "update" );
		registerKeyword( "use" );
		registerKeyword( "user" );
		registerKeyword( "user_option" );
		registerKeyword( "using" );
		registerKeyword( "values" );
		registerKeyword( "varying" );
		registerKeyword( "view" );
		registerKeyword( "waitfor" );
		registerKeyword( "when" );
		registerKeyword( "where" );
		registerKeyword( "while" );
		registerKeyword( "with" );
		registerKeyword( "work" );
		registerKeyword( "writetext" );
		registerKeyword( "xmlextract" );
		registerKeyword( "xmlparse" );
		registerKeyword( "xmltest" );
		registerKeyword( "xmlvalidate" );
	}

// Overridden informational metadata ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~


	@Override
	public boolean supportsCascadeDelete() {
		return false;
	}

	@Override
	public int getMaxAliasLength() {
		return 30;
	}

	@Override
	public int getMaxIdentifierLength() {
		return 255;
		}

	@Override
	public LockingSupport getLockingSupport() {
		return TransactSQLLockingSupport.SYBASE_LEGACY;
	}

	@Override
	public boolean supportsOrderByInSubquery() {
		return false;
	}

	@Override
	public boolean supportsUnionInSubquery() {
		// At least not according to HHH-3637
		return false;
	}

	@Override
	public boolean supportsPartitionBy() {
		return false;
	}

	@Override
	public String getTableTypeString() {
		//HHH-7298 I don't know if this would break something or cause some side affects
		//but it is required to use 'select for update'
		return getVersion().isBefore( 15, 7 ) ? super.getTableTypeString() : " lock datarows";
	}

	@Override
	public boolean supportsExpectedLobUsagePattern() {
		// Earlier Sybase did not support LOB locators at all
		return getVersion().isSameOrAfter( 15, 7 );
	}

	@Override
	public boolean supportsLobValueChangePropagation() {
		return false;
	}

	@Override
	public String appendLockHint(LockOptions mode, String tableName) {
		final String lockHint = super.appendLockHint( mode, tableName );
		return !mode.getLockMode().greaterThan( LockMode.READ ) && mode.getTimeout().milliseconds() == SKIP_LOCKED_MILLI
				? lockHint + " readpast"
				: lockHint;
	}

	@Override
	public String toQuotedIdentifier(String name) {
		if ( name == null || name.isEmpty() ) {
			return name;
		}
		if ( name.charAt( 0 ) == '#' ) {
			// Temporary tables must start with a '#' character,
			// but Sybase doesn't support quoting of such identifiers,
			// so we simply don't apply quoting in this case
			return name;
		}
		return super.toQuotedIdentifier( name );
	}

	@Override
	public ViolatedConstraintNameExtractor getViolatedConstraintNameExtractor() {
		return EXTRACTOR;
	}

	/**
	 * Constraint-name extractor for Sybase ASE constraint violation exceptions.
	 * Orginally contributed by Denny Bartelt.
	 */
	private static final ViolatedConstraintNameExtractor EXTRACTOR =
			new TemplatedViolatedConstraintNameExtractor( sqle -> {
				final String sqlState = extractSqlState( sqle );
				final int errorCode = extractErrorCode( sqle );
				if ( sqlState != null ) {
					switch ( sqlState ) {
						case "S1000":
						case "23000":
							switch ( errorCode ) {
								case 2601:
									// UNIQUE VIOLATION
									return extractUsingTemplate( "with unique index '", "'", sqle.getMessage() );
								case 546:
									// Foreign key violation
									return extractUsingTemplate( "constraint name = '", "'", sqle.getMessage() );
							}
							break;
					}
				}
				return null;
			} );

	@Override
	public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
		if ( getVersion().isBefore( 15, 7 ) ) {
			return null;
		}
		return (sqlException, message, sql) -> {
			final String sqlState = extractSqlState( sqlException );
			final int errorCode = extractErrorCode( sqlException );
			if ( sqlState != null ) {
				switch ( sqlState ) {
					case "HY008":
						return new QueryTimeoutException( message, sqlException, sql );
					case "JZ0TO":
					case "JZ006":
						return new LockTimeoutException( message, sqlException, sql );
					case "S1000":
					case "23000":
						switch ( errorCode ) {
							case 515:
								// Attempt to insert NULL value into column; column does not allow nulls.
								return new ConstraintViolationException(
										message,
										sqlException,
										sql,
										getViolatedConstraintNameExtractor().extractConstraintName( sqlException )
								);
							case 546:
								// Foreign key violation
								return new ConstraintViolationException(
										message,
										sqlException,
										sql,
										getViolatedConstraintNameExtractor().extractConstraintName( sqlException )
								);
							case 2601:
								// Unique constraint violation
								return new ConstraintViolationException(
										message,
										sqlException,
										sql,
										ConstraintViolationException.ConstraintKind.UNIQUE,
										getViolatedConstraintNameExtractor().extractConstraintName( sqlException )
								);
						}
						break;
					case "ZZZZZ":
						if ( 515 == errorCode ) {
							// Attempt to insert NULL value into column; column does not allow nulls.
							return new ConstraintViolationException(
									message,
									sqlException,
									sql,
									getViolatedConstraintNameExtractor().extractConstraintName( sqlException )
							);
						}
						break;
				}
			}
			return null;
		};
	}

	@Override
	public LimitHandler getLimitHandler() {
		if ( getVersion().isBefore( 12, 5 ) ) {
			//support for SELECT TOP was introduced in Sybase ASE 12.5.3
			return super.getLimitHandler();
		}
		return new TopLimitHandler(false);
	}

	@Override
	public String getDual() {
		return "(select 1 c1)";
	}

	@Override
	public boolean supportsIntersect() {
		// At least the version that
		return false;
	}

	@Override
	public boolean supportsJoinsInDelete() {
		return true;
	}

	@Override
	public boolean supportsCrossJoin() {
		return false;
	}
}
