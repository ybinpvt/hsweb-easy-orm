package org.hswebframework.ezorm.rdb.supports.commons;

import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.hswebframework.ezorm.core.meta.ObjectMetadataParser;
import org.hswebframework.ezorm.rdb.executor.SqlRequests;
import org.hswebframework.ezorm.rdb.executor.SyncSqlExecutor;
import org.hswebframework.ezorm.rdb.executor.wrapper.ColumnWrapperContext;
import org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrapper;
import org.hswebframework.ezorm.rdb.mapping.defaults.record.Record;
import org.hswebframework.ezorm.rdb.mapping.defaults.record.RecordResultWrapper;
import org.hswebframework.ezorm.rdb.metadata.*;
import org.hswebframework.ezorm.rdb.metadata.dialect.Dialect;
import org.hswebframework.ezorm.rdb.metadata.parser.IndexMetadataParser;
import org.hswebframework.ezorm.rdb.metadata.parser.TableMetadataParser;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.hswebframework.ezorm.rdb.executor.SqlRequests.template;
import static org.hswebframework.ezorm.rdb.executor.wrapper.ResultWrappers.*;

public abstract class RDBTableMetadataParser implements TableMetadataParser {

    protected RDBSchemaMetadata schema;

    protected SyncSqlExecutor sqlExecutor;

    protected Dialect getDialect() {
        return schema.getDialect();
    }

    protected abstract String getTableMetaSql(String name);

    protected abstract String getTableCommentSql(String name);

    protected abstract String getAllTableSql();

    protected abstract String getTableExistsSql();

    public RDBTableMetadataParser(RDBSchemaMetadata schema) {
        this.schema = schema;
    }

    protected SyncSqlExecutor getSqlExecutor() {
        if (this.sqlExecutor == null) {

            this.sqlExecutor = schema.findFeatureNow(SyncSqlExecutor.ID);
        }
        return this.sqlExecutor;
    }

    protected RDBTableMetadata createTable(String name) {
        return schema.newTable(name);
    }

    @SneakyThrows
    protected Optional<RDBTableMetadata> doParse(String name) {
        RDBTableMetadata metaData = createTable(name);
        metaData.setName(name);
        metaData.setAlias(name);
        Map<String, Object> param = new HashMap<>();
        param.put("table", name);
        param.put("schema", schema.getName());

        //列
        List<RDBColumnMetadata> metaDataList = getSqlExecutor().select(template(getTableMetaSql(name), param),
                list(new RDBColumnMetaDataWrapper(metaData)));
        metaDataList.forEach(metaData::addColumn);
        //说明
        Map<String, Object> comment = getSqlExecutor().select(template(getTableCommentSql(name), param), singleMap());
        if (null != comment && comment.get("comment") != null) {
            metaData.setComment(String.valueOf(comment.get("comment")));
        }
        //加载索引
        schema.<IndexMetadataParser>findFeature(IndexMetadataParser.id)
                .map(parser -> parser.parseTableIndex(name))
                .ifPresent(indexes -> indexes.forEach(metaData::addIndex));

        return Optional.of(metaData);
    }

    @Override
    @SuppressWarnings("all")
    public Optional<RDBTableMetadata> parseByName(String name) {
        if (!tableExists(name)) {
            return Optional.empty();
        }
        return doParse(name);
    }

    @Override
    @SneakyThrows
    public boolean tableExists(String name) {
        Map<String, Object> param = new HashMap<>();
        param.put("table", name);
        param.put("schema", schema.getName());
        return getSqlExecutor()
                .select(template(getTableExistsSql(), param),
                        optional(single(column("total", Number.class::cast))))
                .map(number -> number.intValue() > 0)
                .orElse(false);

    }

    @Override
    @SneakyThrows
    public List<String> parseAllTableName() {
        return getSqlExecutor()
                .select(SqlRequests.template(getAllTableSql(), Collections.singletonMap("schema", schema.getName())), list(column("name", String::valueOf)));
    }


    protected List<RDBTableMetadata> fastParseAll() {
        Map<String, Object> param = new HashMap<>();
        param.put("table", "%%");
        param.put("schema", schema.getName());

        Map<String, RDBTableMetadata> metadata = new ConcurrentHashMap<>();

        //列
        getSqlExecutor()
                .select(
                        template(getTableMetaSql(null), param),
                        consumer(new RecordResultWrapper(), record -> {
                            String tableName = record.getString("table_name").orElseThrow(() -> new NullPointerException("table_name is null"));
                            RDBTableMetadata tableMetadata = metadata.computeIfAbsent(tableName, __t -> {
                                RDBTableMetadata metaData = createTable(__t);
                                metaData.setName(__t);
                                metaData.setAlias(__t);
                                return metaData;
                            });
                            RDBColumnMetadata column = tableMetadata.newColumn();
                            applyColumnInfo(column, record);
                            tableMetadata.addColumn(column);
                        }));

        //说明
        getSqlExecutor()
                .select(template(getTableCommentSql(null), param)
                        , consumer(new RecordResultWrapper(), record -> {

                            record.getString("table_name")
                                    .map(metadata::get)
                                    .ifPresent(table -> record.getString("comment").ifPresent(table::setComment));
                        }));

        //索引
        schema.<IndexMetadataParser>findFeature(IndexMetadataParser.id)
                .map(IndexMetadataParser::parseAll)
                .ifPresent(indexes -> indexes.forEach(index -> {
                    Optional.ofNullable(metadata.get(index.getTableName()))
                            .ifPresent(table -> table.addIndex(index));
                }));


        return new ArrayList<>(metadata.values());
    }

    protected void applyColumnInfo(RDBColumnMetadata column, Record record) {
        record.getString("name").ifPresent(name -> {
            column.setName(name);
            column.setProperty("old-name", name);
        });
        record.getString("comment").ifPresent(column::setComment);

        record.getString("not_null").ifPresent(value -> {
            column.setNotNull("1" .equals(value));
        });

        record.getInteger("data_length").ifPresent(column::setLength);
        record.getInteger("data_precision").ifPresent(column::setPrecision);
        record.getInteger("data_scale").ifPresent(column::setScale);

        record.getString("data_type")
                .map(getDialect()::convertDataType)
                .ifPresent(column::setType);

        column.findFeature(ValueCodecFactory.ID)
                .flatMap(factory -> factory.createValueCodec(column))
                .ifPresent(column::setValueCodec);
    }

    @Override
    @SuppressWarnings("all")
    public List<RDBTableMetadata> parseAll() {

        return parseAllTableName()
                .parallelStream()
                .map(this::doParse)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    @SuppressWarnings("all")
    @AllArgsConstructor
    class RDBColumnMetaDataWrapper implements ResultWrapper<RDBColumnMetadata, RDBColumnMetadata> {
        private RDBTableMetadata tableMetadata;

        public Class<RDBColumnMetadata> getType() {
            return RDBColumnMetadata.class;
        }

        @Override
        public RDBColumnMetadata newRowInstance() {
            return tableMetadata.newColumn();
        }

        @Override
        public RDBColumnMetadata getResult() {
            return null;
        }

        @Override
        public boolean completedWrapRow(RDBColumnMetadata instance) {
            String data_type = instance.getProperty("data_type").toString().toLowerCase();
            int len = instance.getProperty("data_length").toInt();
            int data_precision = instance.getProperty("data_precision").toInt();
            int data_scale = instance.getProperty("data_scale").toInt();
            instance.setLength(len);
            instance.setPrecision(data_precision);
            instance.setScale(data_scale);

            DataType dataType = getDialect().convertDataType(data_type);

            instance.setType(dataType);
            // instance.setDataType(getDialect().buildColumnDataType(instance));

            instance.findFeature(ValueCodecFactory.ID)
                    .flatMap(factory -> factory.createValueCodec(instance))
                    .ifPresent(instance::setValueCodec);
            return true;
        }

        @Override
        public void wrapColumn(ColumnWrapperContext<RDBColumnMetadata> context) {
            doWrap(context.getRowInstance(), context.getColumnLabel(), context.getResult());
        }

        public void doWrap(RDBColumnMetadata instance, String attr, Object value) {
            String stringValue;
            if (value instanceof String) {
                stringValue = ((String) value).toLowerCase();
            } else {
                stringValue = value == null ? "" : value.toString();
            }
            if (attr.equalsIgnoreCase("name")) {
                instance.setName(stringValue);
                instance.setProperty("old-name", stringValue);
            }
            if (attr.equalsIgnoreCase("table-name")) {
                instance.setProperty("tableName", stringValue);
            } else if (attr.equalsIgnoreCase("comment")) {
                instance.setComment(stringValue);
            } else {
                if (attr.toLowerCase().equals("not_null")) {
                    value = "1" .equals(stringValue);
                    instance.setNotNull((boolean) value);
                }
                instance.setProperty(attr.toLowerCase(), value);
            }
        }
    }
}
