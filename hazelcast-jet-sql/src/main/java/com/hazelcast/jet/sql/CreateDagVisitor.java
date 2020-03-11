/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql;

import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.sql.imap.IMapScanPhysicalRel;
import com.hazelcast.jet.sql.schema.JetTable;

import java.util.ArrayDeque;
import java.util.Deque;

import static com.hazelcast.jet.core.Edge.between;

public class CreateDagVisitor {

    private final JetSqlService sqlService;

    private DAG dag;
    private Deque<VertexAndOrdinal> vertexStack = new ArrayDeque<>();

    public CreateDagVisitor(JetSqlService sqlService, DAG dag, Vertex sink) {
        this.sqlService = sqlService;
        this.dag = dag;
        vertexStack.push(new VertexAndOrdinal(sink));
    }

    public void onConnectorFullScan(IMapScanPhysicalRel rel) {
        JetTable table = rel.getTableUnwrapped();
        Tuple2<Vertex, Vertex> subDag = table.getSqlConnector().fullScanReader(dag, table, null, rel.getFilter(),
                rel.getProjects());
        assert subDag != null : "null subDag"; // we check for this earlier TODO check for it earlier :)
        VertexAndOrdinal targetVertex = vertexStack.peek();
        dag.edge(between(subDag.f1(), targetVertex.vertex));
        targetVertex.ordinal++;
    }

    private static final class VertexAndOrdinal {
        final Vertex vertex;
        int ordinal;

        private VertexAndOrdinal(Vertex vertex) {
            this.vertex = vertex;
        }

        @Override
        public String toString() {
            return "{vertex=" + vertex.getName() + ", ordinal=" + ordinal + '}';
        }
    }
}