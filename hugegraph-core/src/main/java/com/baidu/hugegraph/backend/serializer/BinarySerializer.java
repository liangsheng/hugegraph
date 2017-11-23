/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.serializer;

import java.util.Collection;

import org.apache.commons.lang.NotImplementedException;
import org.apache.tinkerpop.gremlin.structure.Direction;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.id.IdGenerator;
import com.baidu.hugegraph.backend.id.SplicingIdGenerator;
import com.baidu.hugegraph.backend.query.ConditionQuery;
import com.baidu.hugegraph.backend.query.IdQuery;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.serializer.BinaryBackendEntry.BinaryId;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.backend.store.BackendEntry.BackendColumn;
import com.baidu.hugegraph.exception.NotFoundException;
import com.baidu.hugegraph.schema.EdgeLabel;
import com.baidu.hugegraph.schema.IndexLabel;
import com.baidu.hugegraph.schema.PropertyKey;
import com.baidu.hugegraph.schema.SchemaElement;
import com.baidu.hugegraph.schema.VertexLabel;
import com.baidu.hugegraph.structure.HugeEdge;
import com.baidu.hugegraph.structure.HugeEdgeProperty;
import com.baidu.hugegraph.structure.HugeElement;
import com.baidu.hugegraph.structure.HugeIndex;
import com.baidu.hugegraph.structure.HugeProperty;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.structure.HugeVertexProperty;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.Cardinality;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.JsonUtil;
import com.baidu.hugegraph.util.StringEncoding;

public class BinarySerializer extends AbstractSerializer {

    @Override
    public BinaryBackendEntry newBackendEntry(HugeType type, Id id) {
        BytesBuffer buffer = BytesBuffer.allocate(1 + id.length());
        BinaryId bid = new BinaryId(buffer.writeId(id).bytes(), id);
        return new BinaryBackendEntry(type, bid);
    }

    private BinaryBackendEntry newBackendEntry(HugeVertex vertex) {
        return newBackendEntry(vertex.type(), vertex.id());
    }

    private BinaryBackendEntry newBackendEntry(HugeEdge edge) {
        BinaryId id = new BinaryId(formatEdgeName(edge),
                                   edge.idWithDirection());
        return new BinaryBackendEntry(HugeType.EDGE, id);
    }

    @SuppressWarnings("unused")
    private BinaryBackendEntry newBackendEntry(SchemaElement elem) {
        Id id = IdGenerator.of(elem);
        return newBackendEntry(elem.type(), id);
    }

    @Override
    protected BinaryBackendEntry convertEntry(BackendEntry entry) {
        assert entry instanceof BinaryBackendEntry;
        return (BinaryBackendEntry) entry;
    }

    protected byte[] formatSyspropName(Id id, HugeKeys col) {
        BytesBuffer buffer = BytesBuffer.allocate(1 + id.length() + 1 + 1);
        byte sysprop = HugeType.SYS_PROPERTY.code();
        return buffer.writeId(id).write(sysprop).write(col.code()).bytes();
    }

    protected byte[] formatSyspropName(BinaryId id, HugeKeys col) {
        BytesBuffer buffer = BytesBuffer.allocate(id.length() + 1 + 1);
        byte sysprop = HugeType.SYS_PROPERTY.code();
        return buffer.write(id.asBytes()).write(sysprop)
                     .write(col.code()).bytes();
    }

    protected BackendColumn formatLabel(HugeElement elem) {
        BackendColumn col = new BackendColumn();
        col.name = this.formatSyspropName(elem.id(), HugeKeys.LABEL);
        // TODO: change to save label id
        col.value = StringEncoding.encode(elem.label());
        return col;
    }

    protected byte[] formatPropertyName(HugeProperty<?> prop) {
        Id id = prop.element().id();
        byte[] name = StringEncoding.encode(prop.key());
        BytesBuffer buffer = BytesBuffer.allocate(1 + id.length() +
                                                  1 + name.length);
        buffer.writeId(id);
        buffer.write(prop.type().code());
        buffer.write(name);
        return buffer.bytes();
    }

    protected byte[] formatPropertyValue(HugeProperty<?> prop) {
        // TODO: serialize to bin instead of json
        return StringEncoding.encode(JsonUtil.toJson(prop.value()));
    }

    protected BackendColumn formatProperty(HugeProperty<?> prop) {
        BackendColumn col = new BackendColumn();
        col.name = this.formatPropertyName(prop);
        col.value = this.formatPropertyValue(prop);
        return col;
    }

    protected void parseProperty(String name, byte[] val, HugeElement owner) {
        PropertyKey pkey = owner.graph().propertyKey(name);

        // Parse value
        Object value = JsonUtil.fromJson(StringEncoding.decode(val),
                                         pkey.clazz());
        // Set properties of vertex/edge
        if (pkey.cardinality() == Cardinality.SINGLE) {
            owner.addProperty(pkey.name(), value);
        } else {
            if (!(value instanceof Collection)) {
                throw new BackendException(
                          "Invalid value of non-single property: %s", value);
            }
            for (Object v : (Collection<?>) value) {
                v = JsonUtil.castNumber(v, pkey.dataType().clazz());
                owner.addProperty(pkey.name(), v);
            }
        }
    }

    protected byte[] formatEdgeName(HugeEdge edge) {
        // source-vertex + dir + edge-label + sort-values + target-vertex

        BytesBuffer buffer = BytesBuffer.allocate(256);

        buffer.writeId(edge.ownerVertex().id());
        buffer.write(edge.type().code());
        buffer.writeString(edge.label()); // TODO: change to id
        buffer.writeString(edge.name()); // TODO: write if need
        buffer.writeId(edge.otherVertex().id());

        return buffer.bytes();
    }

    protected byte[] formatEdgeValue(HugeEdge edge) {
        BytesBuffer buffer = BytesBuffer.allocate(6 * edge.getProperties().size());

        // Write edge id
        //buffer.writeId(edge.id());

        // Write edge properties size
        buffer.writeInt(edge.getProperties().size());

        // Write edge properties data
        for (HugeProperty<?> property : edge.getProperties().values()) {
            buffer.writeString(property.key());
            buffer.writeBytes(this.formatPropertyValue(property));
        }

        return buffer.bytes();
    }

    protected BackendColumn formatEdge(HugeEdge edge) {
        BackendColumn col = new BackendColumn();
        col.name = this.formatEdgeName(edge);
        col.value = this.formatEdgeValue(edge);
        return col;
    }

    protected void parseEdge(BackendColumn col, HugeVertex vertex,
                             HugeGraph graph) {
        // source-vertex + dir + edge-label + sort-values + target-vertex

        BytesBuffer buffer = BytesBuffer.wrap(col.name);
        Id ownerVertexId = buffer.readId();
        byte type = buffer.read();
        String labelName = buffer.readString(); // TODO: change to id
        String sk = buffer.readString();
        Id otherVertexId = buffer.readId();

        if (vertex == null) {
            vertex = new HugeVertex(graph, ownerVertexId, null);
        }

        boolean isOutEdge = (type == HugeType.EDGE_OUT.code());
        EdgeLabel edgeLabel = graph.edgeLabel(labelName);
        VertexLabel srcLabel = graph.vertexLabel(edgeLabel.sourceLabel());
        VertexLabel tgtLabel = graph.vertexLabel(edgeLabel.targetLabel());

        HugeVertex otherVertex;
        if (isOutEdge) {
            vertex.vertexLabel(srcLabel);
            otherVertex = new HugeVertex(graph, otherVertexId, tgtLabel);
        } else {
            vertex.vertexLabel(tgtLabel);
            otherVertex = new HugeVertex(graph, otherVertexId, srcLabel);
        }

        HugeEdge edge = new HugeEdge(graph, null, edgeLabel);

        if (isOutEdge) {
            edge.sourceVertex(vertex);
            edge.targetVertex(otherVertex);
            vertex.addOutEdge(edge);
            otherVertex.addInEdge(edge.switchOwner());
        } else {
            edge.sourceVertex(otherVertex);
            edge.targetVertex(vertex);
            vertex.addInEdge(edge);
            otherVertex.addOutEdge(edge.switchOwner());
        }

        vertex.propNotLoaded();
        otherVertex.propNotLoaded();

        // Write edge-id + edge-properties
        buffer = BytesBuffer.wrap(col.value);

        //Id id = buffer.readId();

        // Write edge properties
        int size = buffer.readInt();
        for (int i = 0; i < size; i++) {
            this.parseProperty(buffer.readString(), buffer.readBytes(), edge);
        }

        edge.name(sk);
        edge.assignId();
    }

    protected void parseColumn(BackendColumn col, HugeVertex vertex) {
        BytesBuffer buffer = BytesBuffer.wrap(col.name);
        buffer.readId();
        byte type = buffer.read();
        // Parse property
        if (type == HugeType.PROPERTY.code()) {
            String name = buffer.readStringFromRemaining();
            this.parseProperty(name, col.value, vertex);
        }
        // Parse edge
        else if (type == HugeType.EDGE_IN.code() ||
                 type == HugeType.EDGE_OUT.code()) {
            this.parseEdge(col, vertex, vertex.graph());
        }
    }

    @Override
    public BackendEntry writeVertex(HugeVertex vertex) {
        BinaryBackendEntry entry = newBackendEntry(vertex);

        if (vertex.removed()) {
            return entry;
        }

        // Write vertex label
        entry.column(this.formatLabel(vertex));

        // Write all properties of a Vertex
        for (HugeProperty<?> prop : vertex.getProperties().values()) {
            entry.column(this.formatProperty(prop));
        }

        return entry;
    }

    @Override
    public BackendEntry writeVertexProperty(HugeVertexProperty<?> prop) {
        BinaryBackendEntry entry = newBackendEntry(prop.element());
        entry.column(this.formatProperty(prop));
        entry.subId(IdGenerator.of(prop.key()));
        return entry;
    }

    @Override
    public HugeVertex readVertex(BackendEntry bytesEntry, HugeGraph graph) {
        if (bytesEntry == null) {
            return null;
        }
        BinaryBackendEntry entry = this.convertEntry(bytesEntry);

        // Parse label
        final byte[] VL = this.formatSyspropName(entry.id(), HugeKeys.LABEL);
        BackendColumn vl = entry.column(VL);
        VertexLabel label = null;
        if (vl != null) {
            // TODO: change to read vl-id
            label = graph.vertexLabel(StringEncoding.decode(vl.value));
        }

        // Parse id
        Id id = entry.id().origin();
        HugeVertex vertex = new HugeVertex(graph, id, label);

        // Parse all properties and edges of a Vertex
        for (BackendColumn col : entry.columns()) {
            this.parseColumn(col, vertex);
        }

        return vertex;
    }

    @Override
    public BackendEntry writeEdge(HugeEdge edge) {
        BinaryBackendEntry entry = newBackendEntry(edge);
        entry.column(this.formatEdge(edge));
        return entry;
    }

    @Override
    public BackendEntry writeEdgeProperty(HugeEdgeProperty<?> prop) {
        // TODO: entry.column(this.formatProperty(prop));
        throw new NotImplementedException("Unsupported writeEdgeProperty()");
    }

    @Override
    public HugeEdge readEdge(BackendEntry entry, HugeGraph graph) {
        throw new NotImplementedException("Unsupported readEdge()");
    }

    @Override
    public BackendEntry writeIndex(HugeIndex index) {
        BinaryBackendEntry entry;
        if (index.fieldValues() == null && index.elementIds().size() == 0) {
            /*
             * When field-values is null and elementIds size is 0, it is
             * meaningful for deletion of index data in secondary/search index.
             * TODO: improve
             */
            Id id = IdGenerator.of(index.indexLabelName());
            entry = new BinaryBackendEntry(index.type(),
                                           new BinaryId(id.asBytes(), id));
        } else {
            Id id = index.id();
            byte[] indexId = id.asBytes();
            E.checkArgument(indexId.length <= BytesBuffer.UINT8_MAX,
                            "Index key must be less than 256, but got: %s",
                            indexId.length);
            Id elemId = index.elementId();
            BytesBuffer buffer = BytesBuffer.allocate(indexId.length +
                                                      1 + elemId.length() + 1);
            buffer.write(indexId);
            buffer.writeId(elemId);
            buffer.writeUInt8(indexId.length);

            // Ensure the original look of the index key
            entry = new BinaryBackendEntry(index.type(),
                                           new BinaryId(indexId, id));
            entry.column(buffer.bytes(), null);
            entry.subId(elemId);
        }
        return entry;
    }

    @Override
    public HugeIndex readIndex(BackendEntry bytesEntry, HugeGraph graph) {
        if (bytesEntry == null) {
            return null;
        }
        BinaryBackendEntry entry = this.convertEntry(bytesEntry);
        // TODO: parse index id from entry.id() as bytes instead of string
        HugeIndex index = HugeIndex.parseIndexId(graph, entry.type(),
                                                 entry.id().origin());
        for (BackendColumn col : entry.columns()) {
            if (col.name.length <= 0) {
                // Ignore
                continue;
            }
            int idLength = col.name[col.name.length - 1];
            BytesBuffer buffer = BytesBuffer.wrap(col.name);
            buffer.read(idLength);
            index.elementIds(buffer.readId());
        }
        return index;
    }

    @Override
    public BackendEntry writeId(HugeType type, Id id) {
        return newBackendEntry(type, id);
    }

    @Override
    public Query writeQuery(Query query) {
        HugeType type = query.resultType();

        // Serialize edge condition query (TODO: add VEQ(for EOUT/EIN))
        if (type == HugeType.EDGE && !query.conditions().isEmpty()) {
            HugeKeys[] keys = new HugeKeys[] {
                    HugeKeys.OWNER_VERTEX,
                    HugeKeys.DIRECTION,
                    HugeKeys.LABEL,
                    HugeKeys.SORT_VALUES,
                    HugeKeys.OTHER_VERTEX
            };

            int count = 0;
            BytesBuffer buffer = BytesBuffer.allocate(256);
            for (HugeKeys key : keys) {
                Object value = ((ConditionQuery) query).condition(key);

                if (value != null) {
                    count++;
                } else {
                    if (key == HugeKeys.DIRECTION) {
                        value = Direction.OUT;
                    } else {
                        break;
                    }
                }

                if (key == HugeKeys.OWNER_VERTEX ||
                    key == HugeKeys.OTHER_VERTEX) {
                    Id id = HugeElement.getIdValue(value);
                    buffer.writeId(id);
                } else if (key == HugeKeys.DIRECTION) {
                    byte t = value == Direction.OUT ?
                             HugeType.EDGE_OUT.code() :
                             HugeType.EDGE_IN.code();
                    buffer.write(t);
                } else if (value instanceof String) {
                    buffer.writeString((String) value);
                } else {
                    assert false : value.getClass();
                }
            }

            if (count > 0) {
                assert count == query.conditions().size();
                IdQuery result = new IdQuery(type, query);
                result.query(new BinaryId(buffer.bytes(), null));
                return result;
            }
        }

        // Serialize id in query
        if (query instanceof IdQuery) {
            IdQuery result = (IdQuery) query.copy();
            result.resetIds();
            for (Id id : query.ids()) {
                if (type == HugeType.EDGE) {
                    // Serialize edge id query (TODO: add class EdgeId)
                    result.query(edgeId(id, Direction.OUT));
                } else {
                    BytesBuffer buffer = BytesBuffer.allocate(1 + id.length());
                    result.query(new BinaryId(buffer.writeId(id).bytes(), id));
                }
            }
            return result;
        }

        return query;
    }

    private static BinaryId edgeId(Id id, Direction dir) {
        BytesBuffer buffer = BytesBuffer.allocate(256);

        // TODO: improve Id split()
        String[] idParts = SplicingIdGenerator.split(id);

        // Ensure edge id with Direction
        // NOTE: we assume the id without Direction if it contains 4 parts
        if (idParts.length == 4) {
            if (dir == Direction.IN) {
                // Swap source-vertex and target-vertex
                String tmp = idParts[0];
                idParts[0] = idParts[3];
                idParts[3] = tmp;
            }
            buffer.writeId(IdGenerator.of(idParts[0])); // long or string
            buffer.write(dir == Direction.OUT ?
                         HugeType.EDGE_OUT.code() :
                         HugeType.EDGE_IN.code());
            buffer.writeString(idParts[1]); // TODO: change to id
            buffer.writeString(idParts[2]); // TODO: write if need
            buffer.writeId(IdGenerator.of(idParts[3]));
        } else if (idParts.length == 5) {
            buffer.writeId(IdGenerator.of(idParts[0])); // long or string
            buffer.write(idParts[1].equals("OUT") ?
                         HugeType.EDGE_OUT.code() :
                         HugeType.EDGE_IN.code());
            buffer.writeString(idParts[2]); // TODO: change to id
            buffer.writeString(idParts[3]); // TODO: write if need
            buffer.writeId(IdGenerator.of(idParts[4]));

        } else {
            throw new NotFoundException("Unsupported ID format: %s", id);
        }

        return new BinaryId(buffer.bytes(), id);
    }

    public static BinaryId splitIdKey(HugeType type, byte[] bytes) {
        // TODO: maybe we can find a better way to parse index id
        if (type == HugeType.SECONDARY_INDEX || type == HugeType.SEARCH_INDEX) {
            int idLength = bytes.length > 0 ? bytes[bytes.length - 1] : 0;
            BytesBuffer buffer = BytesBuffer.wrap(bytes);
            byte[] id = buffer.read(idLength);
            return new BinaryId(id, IdGenerator.of(id, false));
        }
        return BytesBuffer.wrap(bytes).asId();
    }

    // TODO: remove these methods when improving schema serialize
    private static String splitKeyId(byte[] bytes) {
        BytesBuffer buffer = BytesBuffer.wrap(bytes);
        buffer.readId();
        return buffer.readStringFromRemaining();
    }

    private static byte[] joinIdKey(Id id, String key) {
        int size = 1 + id.length() + key.length();
        BytesBuffer buffer = BytesBuffer.allocate(size);
        buffer.writeId(id);
        buffer.writeStringToRemaining(key);
        return buffer.bytes();
    }

    private BackendEntry text2bin(BackendEntry entry) {
        BinaryBackendEntry bin = newBackendEntry(entry.type(), entry.id());
        TextBackendEntry text = (TextBackendEntry) entry;
        for (String name : text.columnNames()) {
            String value = text.column(name);
            bin.column(joinIdKey(entry.id(), name),
                       StringEncoding.encode(value));
        }
        return bin;
    }

    private BackendEntry bin2text(BackendEntry entry) {
        if (entry == null) {
            return null;
        }
        BinaryBackendEntry bin = (BinaryBackendEntry) entry;
        TextBackendEntry text = new TextBackendEntry(null, bin.id().origin());
        for (BackendColumn col : bin.columns()) {
            String name = splitKeyId(col.name);
            String value = StringEncoding.decode(col.value);
            text.column(name, value);
        }
        return text;
    }

    // TODO: improve schema serialize
    private final TextSerializer textSerializer = new TextSerializer();

    @Override
    public BackendEntry writeVertexLabel(VertexLabel vertexLabel) {
        // TODO Auto-generated method stub
        return text2bin(this.textSerializer.writeVertexLabel(vertexLabel));
    }

    @Override
    public VertexLabel readVertexLabel(BackendEntry entry) {
        // TODO Auto-generated method stub
        return this.textSerializer.readVertexLabel(bin2text(entry));
    }

    @Override
    public BackendEntry writeEdgeLabel(EdgeLabel edgeLabel) {
        // TODO Auto-generated method stub
        return text2bin(this.textSerializer.writeEdgeLabel(edgeLabel));
    }

    @Override
    public EdgeLabel readEdgeLabel(BackendEntry entry) {
        // TODO Auto-generated method stub
        return this.textSerializer.readEdgeLabel(bin2text(entry));
    }

    @Override
    public BackendEntry writePropertyKey(PropertyKey propertyKey) {
        // TODO Auto-generated method stub
        return text2bin(this.textSerializer.writePropertyKey(propertyKey));
    }

    @Override
    public PropertyKey readPropertyKey(BackendEntry entry) {
        // TODO Auto-generated method stub
        return this.textSerializer.readPropertyKey(bin2text(entry));
    }

    @Override
    public BackendEntry writeIndexLabel(IndexLabel indexLabel) {
        // TODO Auto-generated method stub
        return text2bin(this.textSerializer.writeIndexLabel(indexLabel));
    }

    @Override
    public IndexLabel readIndexLabel(BackendEntry entry) {
        // TODO Auto-generated method stub
        return this.textSerializer.readIndexLabel(bin2text(entry));
    }
}
