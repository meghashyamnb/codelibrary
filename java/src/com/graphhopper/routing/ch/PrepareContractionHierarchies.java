package com.graphhopper.routing.ch;

import com.graphhopper.coll.MySortedCollection;
import com.graphhopper.routing.DijkstraBidirectionRef;
import com.graphhopper.routing.DijkstraSimple;
import com.graphhopper.routing.Path;
import com.graphhopper.routing.PathBidirRef;
import com.graphhopper.routing.util.AbstractAlgoPreparation;
import com.graphhopper.routing.util.CarStreetType;
import com.graphhopper.routing.util.EdgeLevelFilter;
import com.graphhopper.routing.util.WeightCalculation;
import com.graphhopper.storage.EdgeEntry;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.LevelGraph;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeSkipIterator;
import com.graphhopper.util.GraphUtility;
import com.graphhopper.util.RawEdgeIterator;
import com.graphhopper.util.StopWatch;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class prepares the setGraph for a bidirectional algorithm supporting
 * contraction hierarchies ie. an algorithm returned by createAlgo.
 * <p/>
 * There are several description of contraction hierarchies available. The
 * following is one of the more detailed:
 * http://web.cs.du.edu/~sturtevant/papers/highlevelpathfinding.pdf
 * <p/>
 * The only difference is that we use a skipped edgeId instead of skipped nodes
 * for faster unpacking.
 *
 * @author Peter Karich
 */
public class PrepareContractionHierarchies extends AbstractAlgoPreparation<PrepareContractionHierarchies> {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private LevelGraph g;
	// the most important nodes comes last
	private MySortedCollection sortedNodes;
	private WeightedNode refs[];
	private TIntArrayList originalEdges;
	// shortcut is one direction, speed is only involved while recalculating the endNode weights - see prepareEdges
	static final int scOneDir = CarStreetType.flags(0, false);
	static final int scBothDir = CarStreetType.flags(0, true);
	private Collection<Shortcut> shortcuts = new ArrayList<Shortcut>();
	private EdgeLevelFilterCH edgeFilter;
	private OneToManyDijkstraCH algo;

	@Override
	public PrepareContractionHierarchies setGraph(Graph g) {
		this.g = (LevelGraph) g;
		return this;
	}

	@Override
	public PrepareContractionHierarchies doWork() {
		super.doWork();
		initFromGraph();
		// TODO integrate PrepareRoutingShortcuts -> so avoid all nodes with negative level in the other methods
		// in PrepareShortcuts level 0 and -1 is already used move that to level 1 and 2 so that level 0 stays as uncontracted
		if (!prepareEdges())
			return this;

		if (!prepareNodes())
			return this;
		contractNodes();
		return this;
	}

	PrepareContractionHierarchies initFromGraph() {
		originalEdges = new TIntArrayList(g.nodes() / 2, -1);
		edgeFilter = new EdgeLevelFilterCH(this.g);
		sortedNodes = new MySortedCollection();
		refs = new WeightedNode[g.nodes()];
		return this;
	}

	boolean prepareEdges() {
		boolean empty = true;
		for (RawEdgeIterator it = g.allEdges(); it.next(); ) {
			setOrigEdgeCount(it.edge(), 1);
			empty = false;
		}
		return !empty;
	}

	boolean prepareNodes() {
		int len = g.nodes();
		// minor idea: 1. sort nodes randomly and 2. pre-init with endNode degree
		for (int node = 0; node < len; node++) {
			refs[node] = new WeightedNode(node, 0);
		}

		for (int node = 0; node < len; node++) {
			WeightedNode wn = refs[node];
			wn.priority = calculatePriority(node);
			sortedNodes.insert(wn.node, wn.priority);
		}

		return !sortedNodes.isEmpty();
	}

	void contractNodes() {
		int level = 1;
		int newShortcuts = 0;
		final int updateSize = Math.max(10, sortedNodes.size() / 10);
		int counter = 0;
		int updateCounter = 0;
		StopWatch sw = new StopWatch();
		// no update all => 600k shortcuts and 3min
		while (!sortedNodes.isEmpty()) {
			if (counter % updateSize == 0) {
				// periodically update priorities of ALL nodes
				if (updateCounter > 0 && updateCounter % 2 == 0) {
					int len = g.nodes();
					sw.start();
					// TODO avoid to traverse all nodes -> via a new sortedNodes.iterator()
					for (int node = 0; node < len; node++) {
						WeightedNode wNode = refs[node];
						if (g.getLevel(node) != 0)
							continue;
						int old = wNode.priority;
						wNode.priority = calculatePriority(node);
						sortedNodes.update(node, old, wNode.priority);
					}
					sw.stop();
				}
				updateCounter++;
				logger.info(counter + ", nodes: " + sortedNodes.size() + ", shortcuts:" + newShortcuts
						+ ", updateAllTime:" + sw.getSeconds() + ", " + updateCounter
						+ ", memory:" + "totalMB:" + Runtime.getRuntime().totalMemory() / (1 << 20)
						+ ", usedMB:" + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1 << 20));
			}

			counter++;
			WeightedNode wn = refs[sortedNodes.pollKey()];

			// update priority of current endNode via simulating 'addShortcuts'
			wn.priority = calculatePriority(wn.node);
			if (!sortedNodes.isEmpty() && wn.priority > sortedNodes.peekValue()) {
				// endNode got more important => insert as new value and contract it later
				sortedNodes.insert(wn.node, wn.priority);
				continue;
			}

			// contract!
			newShortcuts += addShortcuts(wn.node);
			g.setLevel(wn.node, level);
			level++;

			// recompute priority of uncontracted neighbors
			EdgeIterator iter = g.getEdges(wn.node);
			while (iter.next()) {
				if (g.getLevel(iter.node()) != 0)
					// already contracted no update necessary
					continue;

				int nn = iter.node();
				WeightedNode neighborWn = refs[nn];
				int tmpOld = neighborWn.priority;
				neighborWn.priority = calculatePriority(nn);
				if (neighborWn.priority != tmpOld) {
					sortedNodes.update(nn, tmpOld, neighborWn.priority);
				}
			}
		}
		logger.info("new shortcuts " + newShortcuts + ", prioNodeCollection:" + sortedNodes);
		// System.out.println("new shortcuts " + newShortcuts);
	}

	/**
	 * Calculates the priority of endNode v without changing the setGraph. Warning:
	 * the calculated priority must NOT depend on priority(v) and therefor
	 * findShortcuts should also not depend on the priority(v). Otherwise
	 * updating the priority before contracting in contractNodes() could lead to
	 * a slowishor even endless loop.
	 */
	int calculatePriority(int v) {
		// set of shortcuts that would be added if endNode v would be contracted next.
		Collection<Shortcut> tmpShortcuts = findShortcuts(v);
		// from shortcuts we can compute the edgeDifference

		// # low influence: with it the shortcut creation is slightly faster
		//
		// |shortcuts(v)| − |{(u, v) | v uncontracted}| − |{(v, w) | v uncontracted}|
		// meanDegree is used instead of outDegree+inDegree as if one endNode is in both directions
		// only one bucket memory is used. Additionally one shortcut could also stand for two directions.
		int degree = GraphUtility.count(g.getEdges(v));
		int edgeDifference = tmpShortcuts.size() - degree;

		// # huge influence: the bigger the less shortcuts gets created and the faster is the preparation
		//
		// every endNode has an 'original edge' number associated. initially it is r=1
		// when a new shortcut is introduced then r of the associated edges is summed up:
		// r(u,w)=r(u,v)+r(v,w) now we can define
		// originalEdgesCount = σ(v) := sum_{ (u,w) ∈ shortcuts(v) } of r(u, w)
		int originalEdgesCount = 0;
		for (Shortcut sc : tmpShortcuts) {
			originalEdgesCount += sc.originalEdges;
		}

		// # lowest influence on preparation speed or shortcut creation count
		// (but according to paper should speed up queries)
		//
		// number of already contracted neighbors of v
		int contractedNeighbors = 0;
		EdgeSkipIterator iter = g.getEdges(v);
		while (iter.next()) {
			if (EdgeIterator.Edge.isValid(iter.skippedEdge()))
				contractedNeighbors++;
		}

		// unterfranken example
		// 10, 50, 1 => 180s preparation, q 3.3ms
		//  2,  4, 1 => 200s preparation, q 3.0ms
		// according to the paper do a simple linear combination of the properties to get the priority
		return 10 * edgeDifference + 50 * originalEdgesCount + contractedNeighbors;
	}

	static class EdgeLevelFilterCH extends EdgeLevelFilter {

		int avoidNode;

		public EdgeLevelFilterCH(LevelGraph g) {
			super(g);
		}

		public EdgeLevelFilterCH setAvoidNode(int node) {
			this.avoidNode = node;
			return this;
		}

		@Override
		public boolean accept() {
			// ignore if it is skipNode or a endNode already contracted
			return avoidNode != node() && graph.getLevel(node()) == 0;
		}
	}

	/**
	 * Finds shortcuts, does not change the underlying setGraph.
	 */
	Collection<Shortcut> findShortcuts(int v) {
		// we can use distance instead of weight, see prepareEdges where distance is overwritten by weight!
		List<NodeCH> goalNodes = new ArrayList<NodeCH>();
		shortcuts.clear();
		EdgeIterator iter1 = g.getIncoming(v);
		// TODO PERFORMANCE collect outgoing nodes (goal-nodes) only once and just skip u
		while (iter1.next()) {
			int u = iter1.node();
			int lu = g.getLevel(u);
			if (lu != 0)
				continue;

			double v_u_weight = iter1.distance();

			// one-to-many extractPath path
			goalNodes.clear();
			EdgeIterator iter2 = g.getOutgoing(v);
			double maxWeight = 0;
			while (iter2.next()) {
				int w = iter2.node();
				int lw = g.getLevel(w);
				if (w == u || lw != 0)
					continue;

				NodeCH n = new NodeCH();
				n.endNode = w;
				n.originalEdges = getOrigEdgeCount(iter2.edge());
				n.distance = v_u_weight + iter2.distance();
				goalNodes.add(n);

				if (maxWeight < n.distance)
					maxWeight = n.distance;
			}

			if (goalNodes.isEmpty())
				continue;

			// TODO instead of a weight-limit we could use a hop-limit
			// and successively increasing it when mean-degree of setGraph increases
			algo = new OneToManyDijkstraCH(g).setFilter(edgeFilter.setAvoidNode(v));
			algo.setLimit(maxWeight).calcPath(u, goalNodes);
			internalFindShortcuts(goalNodes, u, iter1.edge());
		}
		return shortcuts;
	}

	void internalFindShortcuts(List<NodeCH> goalNodes, int u, int skippedEdge) {
		int uOrigEdgeCount = getOrigEdgeCount(skippedEdge);
		for (NodeCH n : goalNodes) {
			if (n.entry != null) {
				Path path = algo.extractPath(n.entry);
				if (path.found() && path.weight() <= n.distance) {
					// FOUND witness path, so do not add shortcut
					continue;
				}
			}

			// FOUND shortcut but be sure that it is the only shortcut in the collection
			// and also in the setGraph for u->w. If existing AND identical length => update flags.
			// Hint: shortcuts are always one-way due to distinct level of every endNode but we don't
			// know yet the levels so we need to determine the correct direction or if both directions

			// minor improvement: if (shortcuts.containsKey((long) n.endNode * refs.length + u))
			// then two shortcuts with the same nodes (u<->n.endNode) exists => check current shortcut against both

			boolean found = false;
			for (Shortcut tmp : shortcuts) {
				if (Double.compare(n.distance, tmp.distance) == 0) {
					// same direction -> no shortcut, no update
					if (tmp.from == u && tmp.to == n.endNode) {
						found = true;
						break;
						// different direction -> no shortcut, update
					} else if (tmp.from == n.endNode && tmp.to == u) {
						tmp.flags = scBothDir;
						found = true;
						break;
					}
					// new shortcut
				}
			}
			if (found)
				continue;
			Shortcut sc = new Shortcut(u, n.endNode, n.distance);
			shortcuts.add(sc);
			sc.skippedEdge = skippedEdge;
			sc.originalEdges = uOrigEdgeCount + n.originalEdges;
		}
	}

	/**
	 * Introduces the necessary shortcuts for endNode v in the setGraph.
	 */
	int addShortcuts(int v) {
		Collection<Shortcut> foundShortcuts = findShortcuts(v);
		int newShortcuts = 0;
		for (Shortcut sc : foundShortcuts) {
			boolean updatedInGraph = false;
			// check if we need to update some existing shortcut in the setGraph
			EdgeSkipIterator iter = g.getOutgoing(sc.from);
			while (iter.next()) {
				if (EdgeIterator.Edge.isValid(iter.skippedEdge())
						&& iter.node() == sc.to
						&& CarStreetType.canBeOverwritten(iter.flags(), sc.flags)
						&& iter.distance() > sc.distance) {
					iter.flags(sc.flags);
					iter.skippedEdge(sc.skippedEdge);
					iter.distance(sc.distance);
					setOrigEdgeCount(iter.edge(), sc.originalEdges);
					updatedInGraph = true;
					break;
				}
			}

			if (!updatedInGraph) {
				iter = g.edge(sc.from, sc.to, sc.distance, sc.flags);
				iter.skippedEdge(sc.skippedEdge);
				setOrigEdgeCount(iter.edge(), sc.originalEdges);
				newShortcuts++;
			}
		}
		return newShortcuts;
	}

	private void setOrigEdgeCount(int index, int value) {
		originalEdges.ensureCapacity(index + 1);
		originalEdges.setQuick(index, value);
	}

	private int getOrigEdgeCount(int index) {
		originalEdges.ensureCapacity(index + 1);
		return originalEdges.getQuick(index);
	}

	@Override
	public DijkstraBidirectionRef createAlgo() {
		// do not change weight within DijkstraBidirectionRef => so use ShortestCalc
		DijkstraBidirectionRef dijkstra = new DijkstraBidirectionRef(g) {
			@Override
			protected void initCollections(int nodes) {
				// algorithm with CH does not need that much memory pre allocated
				super.initCollections(Math.min(10000, nodes));
			}

			@Override
			public boolean checkFinishCondition() {
				// changed finish condition for CH
				if (currFrom == null)
					return currTo.weight >= shortest.weight();
				else if (currTo == null)
					return currFrom.weight >= shortest.weight();
				return currFrom.weight >= shortest.weight() && currTo.weight >= shortest.weight();
			}

			@Override
			protected PathBidirRef createPath() {
				// CH changes the distance in prepareEdges to the weight
				// now we need to transform it back to the real distance
				WeightCalculation wc = new WeightCalculation() {
					@Override
					public String toString() {
						return "INVERSE";
					}

					@Override
					public double getWeight(double distance, int flags) {
						return distance;
					}

					@Override
					public long getTime(double distance, int flags) {
						return (long) revertWeight(distance, flags);
					}

					@Override
					public double revertWeight(double weight, int flags) {
						return weight;
					}
				};
				return new Path4CH(graph);
			}
		};
		dijkstra.edgeFilter(new EdgeLevelFilter(g));
		return dijkstra;
	}

	// we need to use DijkstraSimple as AStar or DijkstraBidirection cannot be efficiently used with multiple goals
	static class OneToManyDijkstraCH extends DijkstraSimple {

		EdgeLevelFilter filter;
		double limit;
		Collection<NodeCH> goals;

		public OneToManyDijkstraCH(Graph graph) {
			super(graph);
		}

		public OneToManyDijkstraCH setFilter(EdgeLevelFilter filter) {
			this.filter = filter;
			return this;
		}

		@Override
		protected final EdgeIterator neighbors(int neighborNode) {
			return filter.doFilter(super.neighbors(neighborNode));
		}

		OneToManyDijkstraCH setLimit(double weight) {
			limit = weight;
			return this;
		}

		@Override
		public Path calcPath(int from, int to) {
			throw new IllegalArgumentException("call the other calcPath instead");
		}

		Path calcPath(int from, Collection<NodeCH> goals) {
			this.goals = goals;
			return super.calcPath(from, -1);
		}

		@Override
		public boolean finished(EdgeEntry curr, int _ignoreTo) {
			if (curr.weight > limit)
				return true;

			int found = 0;
			for (NodeCH n : goals) {
				if (n.endNode == curr.endNode) {
					n.entry = curr;
					found++;
				} else if (n.entry != null) {
					found++;
				}
			}
			return found == goals.size();
		}
	}

	private static class WeightedNode {

		int node;
		int priority;

		public WeightedNode(int node, int priority) {
			this.node = node;
			this.priority = priority;
		}
	}

	static class Shortcut {

		int from;
		int to;
		int skippedEdge;
		double distance;
		int originalEdges;
		int flags = scOneDir;

		public Shortcut(int from, int to, double dist) {
			this.from = from;
			this.to = to;
			this.distance = dist;
		}

		@Override
		public String toString() {
			return from + "->" + to + ", dist:" + distance;
		}
	}

	static class NodeCH {

		int endNode;
		int originalEdges;
		EdgeEntry entry;
		double distance;

		@Override
		public String toString() {
			return "" + endNode;
		}
	}
}
