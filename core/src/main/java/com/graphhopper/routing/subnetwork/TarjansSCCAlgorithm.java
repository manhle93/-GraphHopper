/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.subnetwork;

import com.carrotsearch.hppc.BitSet;
import com.carrotsearch.hppc.IntArrayDeque;
import com.carrotsearch.hppc.IntArrayList;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Implementation of Tarjan's algorithm using an explicit stack. The traditional recursive approach
 * runs into stack overflow pretty quickly. The algorithm is used within GraphHopper to find
 * strongly connected components to detect dead-ends leading to routes not found and it is used for landmark
 * calculation as well.
 * <p>
 * See http://en.wikipedia.org/wiki/Tarjan's_strongly_connected_components_algorithm. See
 * http://www.timl.id.au/?p=327 and http://homepages.ecs.vuw.ac.nz/~djp/files/P05.pdf
 */
public class TarjansSCCAlgorithm {
    private final List<IntArrayList> components = new ArrayList<>();
    private final Graph graph;
    private final IntArrayDeque nodeStack;
    private final BitSet onStack;
    private final int[] nodeIndex;
    private final int[] nodeLowLink;
    private final boolean excludeSingleNodeComponents;
    private final EdgeFilter outFilter;
    private EdgeFilter edgeFilter;
    private int index = 1;

    public TarjansSCCAlgorithm(Graph graph, final BooleanEncodedValue accessEnc, boolean excludeSingleNodeComponents) {
        this.graph = graph;
        this.nodeStack = new IntArrayDeque();
        this.onStack = new BitSet(graph.getNodes());
        this.nodeIndex = new int[graph.getNodes()];
        this.nodeLowLink = new int[graph.getNodes()];
        this.outFilter = DefaultEdgeFilter.outEdges(accessEnc);
        this.edgeFilter = outFilter;
        this.excludeSingleNodeComponents = excludeSingleNodeComponents;
    }

    /**
     * Allows adding an additional edge filter to exclude edges while searching for connected components.
     */
    public void setAdditionalEdgeFilter(final EdgeFilter additionalFilter) {
        this.edgeFilter = new EdgeFilter() {
            @Override
            public boolean accept(EdgeIteratorState edgeState) {
                return outFilter.accept(edgeState) && additionalFilter.accept(edgeState);
            }
        };
    }

    /**
     * Find and return a list of all strongly connected components of g. Components with only a single node will
     * be excluded from the result depending on {@link #excludeSingleNodeComponents}
     */
    public List<IntArrayList> findComponents() {
        int nodes = graph.getNodes();
        for (int start = 0; start < nodes; start++) {
            if (nodeIndex[start] == 0)
                strongConnect(start);
        }

        return components;
    }

    /**
     * Find all components reachable from firstNode, add them to 'components'
     *
     * @param firstNode start search of SCC at this node
     */
    private void strongConnect(int firstNode) {
        final Stack<TarjanState> stateStack = new Stack<>();
        stateStack.push(TarjanState.startState(firstNode));

        // nextState label is equivalent to the function entry point in the recursive Tarjan's algorithm.
        nextState:

        while (!stateStack.empty()) {
            TarjanState state = stateStack.pop();
            final int start = state.start;
            final EdgeIterator iter;

            if (state.isStart()) {
                // We're traversing a new node 'start'.  Set the depth index for this node to the smallest unused index.
                nodeIndex[start] = index;
                nodeLowLink[start] = index;
                index++;
                nodeStack.addLast(start);
                onStack.set(start);

                iter = graph.createEdgeExplorer(edgeFilter).setBaseNode(start);

            } else {
                // We're resuming iteration over the next child of 'start', set lowLink as appropriate.
                iter = state.iter;

                int prevConnectedId = iter.getAdjNode();
                nodeLowLink[start] = Math.min(nodeLowLink[start], nodeLowLink[prevConnectedId]);
            }

            // Each element (excluding the first) in the current component should be able to find
            // a successor with a lower nodeLowLink.
            while (iter.next()) {
                int connectedId = iter.getAdjNode();
                if (nodeIndex[connectedId] == 0) {
                    // Push resume and start states onto state stack to continue our DFS through the graph after the jump.
                    // Ideally we'd just call strongConnectIterative(connectedId);
                    stateStack.push(TarjanState.resumeState(start, iter));
                    stateStack.push(TarjanState.startState(connectedId));
                    continue nextState;
                } else if (onStack.get(connectedId)) {
                    nodeLowLink[start] = Math.min(nodeLowLink[start], nodeIndex[connectedId]);
                }
            }

            // If nodeLowLink == nodeIndex, then we are the first element in a component.
            // Add all nodes higher up on nodeStack to this component.
            if (nodeIndex[start] == nodeLowLink[start]) {
                // in case we are excluding single-node components from the result we do not need to create the array
                if (excludeSingleNodeComponents && nodeStack.getLast() == start) {
                    nodeStack.removeLast();
                    onStack.clear(start);
                    continue;
                }
                IntArrayList component = new IntArrayList(nodeStack.getLast() == start ? 1 : 64);
                while (true) {
                    int node = nodeStack.removeLast();
                    component.add(node);
                    onStack.clear(node);
                    if (node == start)
                        break;
                }
                // trimming the component arrays is important to reduce memory usage as there are very many components
                // with only a single node, especially when using multiple vehicles (e.g. inner cities with lots of
                // foot ways that are not accessible for cars), see also #520
                component.trimToSize();
                components.add(component);
            }
        }
    }

    /**
     * Internal stack state of algorithm, used to avoid recursive function calls and hitting stack
     * overflow exceptions. State is either 'start' for new nodes or 'resume' for partially
     * traversed nodes.
     */
    private static class TarjanState {
        final int start;
        final EdgeIterator iter;

        private TarjanState(final int start, final EdgeIterator iter) {
            this.start = start;
            this.iter = iter;
        }

        public static TarjanState startState(int start) {
            return new TarjanState(start, null);
        }

        public static TarjanState resumeState(int start, EdgeIterator iter) {
            return new TarjanState(start, iter);
        }

        // Iterator only present in 'resume' state.
        boolean isStart() {
            return iter == null;
        }
    }
}
