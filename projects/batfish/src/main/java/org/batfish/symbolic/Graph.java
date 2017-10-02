package org.batfish.symbolic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.BgpNeighbor;
import org.batfish.datamodel.BgpProcess;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Edge;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.OspfProcess;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.Topology;
import org.batfish.datamodel.collections.EdgeSet;
import org.batfish.datamodel.collections.NodeInterfacePair;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.Conjunction;
import org.batfish.datamodel.routing_policy.expr.ExplicitPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.expr.Not;
import org.batfish.datamodel.routing_policy.expr.PrefixSetExpr;
import org.batfish.symbolic.smt.collections.Table2;

/**
 * A graph object representing the structure of the network. The graph is built potentially by
 * inferring the link connectivity of the network based on interface ip addresses. Interfaces are
 * abstracted into graph edges, which include a direction. Each graph edge can be mapped to its
 * opposite edge, which may be null, if there is no such edge (e.g., for loopbacks).
 *
 * <p>iBGP is modeled as additional "abstract" edges that do not correspond to any concrete edge in
 * the topology.
 *
 * @author Ryan Beckett
 */
public class Graph {

  static final int DEFAULT_CISCO_VLAN_OSPF_COST = 1;

  public static final String BGP_COMMON_FILTER_LIST_NAME = "BGP_COMMON_EXPORT_POLICY";

  private static final String NULL_INTERFACE_NAME = "null_interface";

  private IBatfish _batfish;

  private Map<String, Configuration> _configurations;

  private Map<String, Set<Long>> _areaIds;

  private Map<String, Map<String, List<StaticRoute>>> _staticRoutes;

  private Map<String, Set<String>> _neighbors;

  private Map<String, List<GraphEdge>> _edgeMap;

  private Map<GraphEdge, GraphEdge> _otherEnd;

  private Map<GraphEdge, BgpNeighbor> _ebgpNeighbors;

  private Map<GraphEdge, BgpNeighbor> _ibgpNeighbors;

  private Map<String, String> _routeReflectorParent;

  private Map<String, Set<String>> _routeReflectorClients;

  private Map<String, Integer> _originatorId;

  private Map<String, Integer> _domainMap;

  private Map<Integer, Set<String>> _domainMapInverse;

  /*
   * Create a graph from a Batfish object
   */
  public Graph(IBatfish batfish) {
    this(batfish, null);
  }

  /*
   * Create a graph, while selecting the subset of routers to use.
   */
  public Graph(IBatfish batfish, @Nullable Set<String> routers) {
    _batfish = batfish;
    _configurations = new HashMap<>(_batfish.loadConfigurations());
    _edgeMap = new HashMap<>();
    _otherEnd = new HashMap<>();
    _areaIds = new HashMap<>();
    _staticRoutes = new HashMap<>();
    _neighbors = new HashMap<>();
    _ebgpNeighbors = new HashMap<>();
    _ibgpNeighbors = new HashMap<>();
    _routeReflectorParent = new HashMap<>();
    _routeReflectorClients = new HashMap<>();
    _originatorId = new HashMap<>();
    _domainMap = new HashMap<>();
    _domainMapInverse = new HashMap<>();

    // Remove the routers we don't want to model
    if (routers != null) {
      List<String> toRemove = new ArrayList<>();
      _configurations.forEach(
          (router, conf) -> {
            if (!routers.contains(router)) {
              toRemove.add(router);
            }
          });
      for (String router : toRemove) {
        _configurations.remove(router);
      }
    }

    initGraph();
    initOspfCosts();
    initStaticRoutes();
    initEbgpNeighbors();
    initIbgpNeighbors();
    initAreaIds();
    initDomains();
  }

  /*
   * Initialize the topology by inferring interface pairs and
   * create the opposite edge mapping.
   */
  private void initGraph() {
    Topology topology = _batfish.computeTopology(_configurations);
    Map<NodeInterfacePair, Interface> ifaceMap = new HashMap<>();
    Map<String, Set<NodeInterfacePair>> routerIfaceMap = new HashMap<>();

    _configurations.forEach(
        (router, conf) -> {
          Set<NodeInterfacePair> ifacePairs = new HashSet<>();
          conf.getInterfaces()
              .forEach(
                  (name, iface) -> {
                    NodeInterfacePair nip = new NodeInterfacePair(router, name);
                    ifacePairs.add(nip);
                    ifaceMap.put(nip, iface);
                  });
          routerIfaceMap.put(router, ifacePairs);
        });

    Map<NodeInterfacePair, EdgeSet> ifaceEdges = topology.getInterfaceEdges();

    _neighbors = new HashMap<>();
    routerIfaceMap.forEach(
        (router, nips) -> {
          List<GraphEdge> graphEdges = new ArrayList<>();

          Set<String> neighs = new HashSet<>();
          nips.forEach(
              (nip) -> {
                EdgeSet es = ifaceEdges.get(nip);
                Interface i1 = ifaceMap.get(nip);
                boolean hasNoOtherEnd = (es == null && i1.getPrefix() != null);
                if (hasNoOtherEnd) {
                  GraphEdge ge = new GraphEdge(i1, null, router, null, false);
                  graphEdges.add(ge);
                }

                if (es != null) {
                  boolean hasMultipleEnds = (es.size() > 2);
                  if (hasMultipleEnds) {
                    GraphEdge ge = new GraphEdge(i1, null, router, null, false);
                    graphEdges.add(ge);
                  } else {
                    for (Edge e : es) {
                      if (!router.equals(e.getNode2())) {
                        Interface i2 = ifaceMap.get(e.getInterface2());
                        String neighbor = e.getNode2();
                        GraphEdge ge1 = new GraphEdge(i1, i2, router, neighbor, false);
                        GraphEdge ge2 = new GraphEdge(i2, i1, neighbor, router, false);
                        _otherEnd.put(ge1, ge2);
                        graphEdges.add(ge1);
                        neighs.add(neighbor);
                      }
                    }
                  }
                }
              });

          _edgeMap.put(router, graphEdges);
          _neighbors.put(router, neighs);
        });
  }

  /** TODO: This was copied from BdpDataPlanePlugin.java to initialize the OSPF inteface costs */
  private void initOspfInterfaceCosts(Configuration conf) {
    if (conf.getDefaultVrf().getOspfProcess() != null) {
      conf.getInterfaces()
          .forEach(
              (interfaceName, i) -> {
                if (i.getActive()) {
                  Integer ospfCost = i.getOspfCost();
                  if (ospfCost == null) {
                    if (interfaceName.startsWith("Vlan")) {
                      // TODO: fix for non-cisco
                      ospfCost = DEFAULT_CISCO_VLAN_OSPF_COST;
                    } else {
                      if (i.getBandwidth() != null) {
                        ospfCost =
                            Math.max(
                                (int)
                                    (conf.getDefaultVrf().getOspfProcess().getReferenceBandwidth()
                                        / i.getBandwidth()),
                                1);
                      } else {
                        throw new BatfishException(
                            "Expected non-null interface "
                                + "bandwidth"
                                + " for \""
                                + conf.getHostname()
                                + "\":\""
                                + interfaceName
                                + "\"");
                      }
                    }
                  }
                  i.setOspfCost(ospfCost);
                }
              });
    }
  }

  /*
 * Initialize the ospf interface costs for each configuration
 */
  private void initOspfCosts() {
    _configurations.forEach((router,conf) -> {
      initOspfInterfaceCosts(conf);
    });
  }

  /*
   * Check if a static route is configured to drop packets
   */
  public static boolean isNullRouted(StaticRoute sr) {
    return sr.getNextHopInterface().equals(NULL_INTERFACE_NAME);
  }


  /*
   * Collect all static routes after inferring which interface they indicate
   * should be used for the next-hop.
   */
  private void initStaticRoutes() {
    _configurations.forEach(
        (router, conf) -> {
          Map<String, List<StaticRoute>> map = new HashMap<>();
          _staticRoutes.put(router, map);

          for (StaticRoute sr : conf.getDefaultVrf().getStaticRoutes()) {

            boolean someIface = false;

            for (GraphEdge ge : _edgeMap.get(router)) {
              Interface here = ge.getStart();
              Interface there = ge.getEnd();

              // Check if next-hop interface is specified
              String hereName = here.getName();
              someIface = true;
              if (hereName.equals(sr.getNextHopInterface())) {
                List<StaticRoute> srs = map.getOrDefault(hereName, new ArrayList<>());
                srs.add(sr);
                map.put(hereName, srs);
              }

              // Check if next-hop ip corresponds to direct interface
              Ip nhIp = sr.getNextHopIp();

              boolean isNextHop =
                  there != null
                      && there.getPrefix() != null
                      && there.getPrefix().getAddress().equals(nhIp);

              if (isNextHop) {
                someIface = true;
                List<StaticRoute> srs = map.getOrDefault(hereName, new ArrayList<>());
                srs.add(sr);
                map.put(there.getName(), srs);
              }
            }

            if (!someIface && !Graph.isNullRouted(sr)) {
              throw new BatfishException(
                  "Router "
                      + router
                      + " has static route: "
                      + sr.getNextHopInterface()
                      + "("
                      + sr.getNetwork()
                      + ")"
                      + " for non next-hop");
            }
          }
        });
  }

  /*
   * Initialize external eBGP neighbors by looking for BGP neighbors
   * where their is no neighbor in the configurations, and the IPs align.
   */
  private void initEbgpNeighbors() {
    Map<String, List<Ip>> ips = new HashMap<>();
    Map<String, List<BgpNeighbor>> neighbors = new HashMap<>();

    _configurations.forEach(
        (router, conf) -> {
          List<Ip> ipList = new ArrayList<>();
          List<BgpNeighbor> ns = new ArrayList<>();
          ips.put(router, ipList);
          neighbors.put(router, ns);
          if (conf.getDefaultVrf().getBgpProcess() != null) {
            conf.getDefaultVrf()
                .getBgpProcess()
                .getNeighbors()
                .forEach(
                    (pfx, neighbor) -> {
                      ipList.add(neighbor.getAddress());
                      ns.add(neighbor);
                    });
          }
        });

    _configurations.forEach(
        (router, conf) -> {
          List<Ip> ipList = ips.get(router);
          List<BgpNeighbor> ns = neighbors.get(router);
          if (conf.getDefaultVrf().getBgpProcess() != null) {
            _edgeMap
                .get(router)
                .forEach(
                    ge -> {
                      for (int i = 0; i < ipList.size(); i++) {
                        Ip ip = ipList.get(i);
                        BgpNeighbor n = ns.get(i);
                        Interface iface = ge.getStart();
                        if (ip != null && iface.getPrefix().contains(ip)) {
                          _ebgpNeighbors.put(ge, n);
                        }
                      }
                    });
          }
        });
  }

  /*
   * Create a new "fake" interface to correspond to an abstract
   * iBGP control plane edge in the network.
   */
  private Interface createIbgpInterface(BgpNeighbor n, String peer) {
    Interface iface = new Interface("iBGP-" + peer);
    iface.setActive(true);
    iface.setPrefix(n.getPrefix());
    return iface;
  }

  // TODO: very inefficient
  /*
   * Initialize iBGP neighbors by looking for nieghbors
   * with the same AS number.
   */
  private void initIbgpNeighbors() {
    Map<String, Ip> ips = new HashMap<>();

    Table2<String, String, BgpNeighbor> neighbors = new Table2<>();

    // Match iBGP sessions with pairs of routers and BgpNeighbor
    _configurations.forEach(
        (router, conf) -> {
          BgpProcess p = conf.getDefaultVrf().getBgpProcess();
          if (p != null) {
            p.getNeighbors()
                .forEach(
                    (pfx, n) -> {
                      if (n.getLocalAs().equals(n.getRemoteAs())) {
                        ips.put(router, n.getLocalIp());
                      }
                    });
          }
        });

    _configurations.forEach(
        (router, conf) -> {
          BgpProcess p = conf.getDefaultVrf().getBgpProcess();
          if (p != null) {
            p.getNeighbors()
                .forEach(
                    (pfx, n) -> {
                      if (n.getLocalAs().equals(n.getRemoteAs())) {
                        ips.forEach(
                            (r, ip) -> {
                              if (!router.equals(r) && pfx.contains(ip)) {
                                neighbors.put(router, r, n);
                              }
                            });
                      }
                    });
          }
        });

    // Add abstract graph edges for iBGP sessions
    Table2<String, String, GraphEdge> reverse = new Table2<>();

    neighbors.forEach(
        (r1, r2, n1) -> {
          Interface iface1 = createIbgpInterface(n1, r2);

          BgpNeighbor n2 = neighbors.get(r2, r1);

          GraphEdge ge;
          if (n2 != null) {
            Interface iface2 = createIbgpInterface(n2, r1);
            ge = new GraphEdge(iface1, iface2, r1, r2, true);
          } else {
            ge = new GraphEdge(iface1, null, r1, null, true);
          }

          _ibgpNeighbors.put(ge, n1);

          reverse.put(r1, r2, ge);

          List<GraphEdge> edges = _edgeMap.get(r1);
          if (edges != null) {
            edges.add(ge);
          } else {
            edges = new ArrayList<>();
            edges.add(ge);
            _edgeMap.put(r1, edges);
          }
        });

    // Add other end to ibgp edges
    reverse.forEach(
        (r1, r2, ge1) -> {
          GraphEdge ge2 = reverse.get(r2, r1);
          _otherEnd.put(ge1, ge2);
        });

    // Configure Route Reflector information
    Integer[] id = new Integer[1];
    id[0] = 1;
    neighbors.forEach(
        (r1, ns) -> {
          if (!_originatorId.containsKey(r1)) {
            _originatorId.put(r1, id[0]);
            id[0]++;
          }
          Set<String> clients = new HashSet<>();
          ns.forEach(
              (r2, n) -> {
                if (n.getRouteReflectorClient()) {
                  clients.add(r2);
                  _routeReflectorParent.put(r2, r1);
                }
              });
          _routeReflectorClients.put(r1, clients);
        });

    /* _routeReflectorClients.forEach((r1, cs) -> {
        System.out.println("Router: " + r1);
        for (String r2 : cs) {
            System.out.println("  client: " + r2 + ", id: " + _originatorId.get(r2));
        }
    }); */

  }

  public enum BgpSendType {
    TO_EBGP,
    TO_NONCLIENT,
    TO_CLIENT,
    TO_RR
  }

  public BgpSendType peerType(GraphEdge ge) {
    if (_ebgpNeighbors.get(ge) != null) {
      return BgpSendType.TO_EBGP;
    }
    if (_ibgpNeighbors.get(ge) != null) {
      Set<String> clients = _routeReflectorClients.get(ge.getPeer());
      Set<String> clients2 = _routeReflectorClients.get(ge.getRouter());
      if (clients != null && clients.contains(ge.getRouter())) {
        return BgpSendType.TO_RR;
      } else if (clients2 != null && clients2.contains(ge.getPeer())) {
        return BgpSendType.TO_CLIENT;
      } else {
        return BgpSendType.TO_NONCLIENT;
      }
    }
    throw new BatfishException("Invalid BGP edge: " + ge);
  }

  /*
   * Initialize each routers set of area IDs for OSPF
   */
  private void initAreaIds() {
    _configurations.forEach(
        (router, conf) -> {
          Set<Long> areaIds = new HashSet<>();
          OspfProcess p = conf.getDefaultVrf().getOspfProcess();
          if (p != null) {
            p.getAreas()
                .forEach(
                    (id, area) -> {
                      areaIds.add(id);
                    });
          }
          _areaIds.put(router, areaIds);
        });
  }

  /*
 * Determines the collection of routers within the same AS
 * as the router provided as a parameter
 */
  private Set<String> findDomain(String router) {
    Set<String> sameDomain = new HashSet<>();
    Queue<String> todo = new ArrayDeque<>();
    todo.add(router);
    while (!todo.isEmpty()) {
      router = todo.remove();
      sameDomain.add(router);
      for (GraphEdge ge : getEdgeMap().get(router)) {
        String peer = ge.getPeer();
        BgpNeighbor n = _ebgpNeighbors.get(ge);
        if (peer != null && n == null && !sameDomain.contains(peer)) {
          todo.add(peer);
        }
      }
    }
    return sameDomain;
  }

  /*
   * Breaks the network up into a collection of devices that
   * are in the same autonomous system. This is useful when
   * modeling iBGP since we can restrict
   */
  private void initDomains() {
    int i = 0;
    Set<String> routers =  new HashSet<>(_configurations.keySet());
    while (!routers.isEmpty()) {
      String router = routers.iterator().next();
      Set<String> domain = findDomain(router);
      _domainMapInverse.put(i, domain);
      for (String r : domain) {
        _domainMap.put(r, i);
        routers.remove(r);
      }
      i++;
    }
  }

  /*
   * Get all the routers in the same AS as the
   * router provided to the function.
   */
  public Set<String> getDomain(String router) {
    int idx = _domainMap.get(router);
    return _domainMapInverse.get(idx);
  }

  /*
   * Check if an interface is active for a particular protocol.
   */
  public boolean isInterfaceActive(Protocol proto, Interface iface) {
    if (proto.isOspf()) {
      return iface.getActive() && iface.getOspfEnabled();
    }
    return iface.getActive();
  }

  /*
   * Check if a topology edge is used in a particular protocol.
   */
  public boolean isEdgeUsed(Configuration conf, Protocol proto, GraphEdge ge) {
    Interface iface = ge.getStart();

    // Don't use if interface is not active
    if (!isInterfaceActive(proto, iface)) {
      return false;
    }

    // Exclude abstract iBGP edges from all protocols except BGP
    if (iface.getName().startsWith("iBGP-")) {
      return proto.isBgp();
    }
    // Never use Loopbacks for any protocol except connected
    if (ge.getStart().isLoopback(conf.getConfigurationFormat())) {
      return proto.isConnected();
    }

    // Don't use ospf over edges to hosts / external
    if (ge.getPeer() == null && proto.isOspf()) {
      return false;
    }

    // Only use specified edges from static routes
    if (proto.isStatic()) {
      List<StaticRoute> srs = getStaticRoutes().get(conf.getName()).get(iface.getName());
      return iface.getActive() && srs != null && srs.size() > 0;
    }

    // Only use an edge in BGP if there is an explicit peering
    if (proto.isBgp()) {
      BgpNeighbor n1 = _ebgpNeighbors.get(ge);
      BgpNeighbor n2 = _ibgpNeighbors.get(ge);
      return n1 != null || n2 != null;
    }

    return true;
  }

  /*
   * Determine if an edge is potentially attached to a host
   */
  public boolean isEdgeHostConnected(GraphEdge ge) {
    boolean isBgpPeering = getEbgpNeighbors().get(ge) != null;
    if (isBgpPeering) {
      return false;
    }
    if (ge.getPeer() == null) {
      return true;
    }
    Configuration peerConf = _configurations.get(ge.getPeer());
    String vendor = peerConf.getConfigurationFormat().getVendorString();
    return "host".equals(vendor);
  }

  /*
   * Find the common (default) routing policy for the protocol.
   */
  @Nullable
  public static RoutingPolicy findCommonRoutingPolicy(Configuration conf, Protocol proto) {
    if (proto.isOspf()) {
      String exp = conf.getDefaultVrf().getOspfProcess().getExportPolicy();
      return conf.getRoutingPolicies().get(exp);
    }
    if (proto.isBgp()) {
      for (Map.Entry<String, RoutingPolicy> entry : conf.getRoutingPolicies().entrySet()) {
        String name = entry.getKey();
        if (name.contains(BGP_COMMON_FILTER_LIST_NAME)) {
          return entry.getValue();
        }
      }
      return null;
    }
    if (proto.isStatic()) {
      return null;
    }
    if (proto.isConnected()) {
      return null;
    }
    throw new BatfishException("TODO: findCommonRoutingPolicy for " + proto.name());
  }

  /*
   * Find the BGP neighbor of a particular edge
   */
  public BgpNeighbor findBgpNeighbor(GraphEdge e) {
    if (e.isAbstract()) {
      return _ibgpNeighbors.get(e);
    } else {
      return _ebgpNeighbors.get(e);
    }
  }

  /* TODO: move this to Logical Graph
   * Find the import routing policy for a given edge
   */
  @Nullable
  public RoutingPolicy findImportRoutingPolicy(String router, Protocol proto, GraphEdge ge) {
    Configuration conf = _configurations.get(router);
    if (proto.isConnected()) {
      return null;
    }
    if (proto.isStatic()) {
      return null;
    }
    if (proto.isOspf()) {
      return null;
    }
    if (proto.isBgp()) {
      BgpNeighbor n = findBgpNeighbor(ge);
      if (n == null || n.getImportPolicy() == null) {
        return null;
      }
      return conf.getRoutingPolicies().get(n.getImportPolicy());
    }
    throw new BatfishException("TODO: findImportRoutingPolicy: " + proto.name());
  }

  /*
   * Find the export routing policy for a given edge
   */
  @Nullable
  public RoutingPolicy findExportRoutingPolicy(String router, Protocol proto, GraphEdge ge) {
    Configuration conf = _configurations.get(router);
    if (proto.isConnected()) {
      return null;
    }
    if (proto.isStatic()) {
      return null;
    }
    if (proto.isOspf()) {
      String exp = conf.getDefaultVrf().getOspfProcess().getExportPolicy();
      return conf.getRoutingPolicies().get(exp);
    }
    if (proto.isBgp()) {
      BgpNeighbor n = findBgpNeighbor(ge);
      // if no neighbor (e.g., loopback), or no export policy
      if (n == null || n.getExportPolicy() == null) {
        return null;
      }
      return conf.getRoutingPolicies().get(n.getExportPolicy());
    }
    throw new BatfishException("TODO: findExportRoutingPolicy for " + proto.name());
  }

  /*
   * Collects and returns all originated prefixes for the given
   * router as well as the protocol. Static routes and connected
   * routes are treated as originating the prefix.
   */
  public static List<Prefix> getOriginatedNetworks(Configuration conf, Protocol proto) {
    List<Prefix> acc = new ArrayList<>();

    if (proto.isOspf()) {
      conf.getDefaultVrf()
          .getOspfProcess()
          .getAreas()
          .forEach(
              (areaID, area) -> {
                for (Interface iface : area.getInterfaces()) {
                  if (iface.getActive() && iface.getOspfEnabled()) {
                    acc.add(iface.getPrefix());
                  }
                }
              });
      return acc;
    }

    if (proto.isBgp()) {
      RoutingPolicy defaultPol = findCommonRoutingPolicy(conf, Protocol.BGP);
      if (defaultPol != null) {
        AstVisitor v = new AstVisitor();
        v.visit(
            conf,
            defaultPol.getStatements(),
            stmt -> { },
            expr -> {
              if (expr instanceof Conjunction) {
                Conjunction c = (Conjunction) expr;
                if (c.getConjuncts().size() >= 2) {
                  BooleanExpr be1 = c.getConjuncts().get(0);
                  BooleanExpr be2 = c.getConjuncts().get(1);
                  if (be1 instanceof MatchPrefixSet && be2 instanceof Not) {
                    MatchPrefixSet mps = (MatchPrefixSet) be1;
                    Not n = (Not) be2;
                    if (n.getExpr() instanceof MatchProtocol) {
                      MatchProtocol mp = (MatchProtocol) n.getExpr();
                      if (mp.getProtocol() == RoutingProtocol.BGP) {
                        PrefixSetExpr e = mps.getPrefixSet();
                        if (e instanceof ExplicitPrefixSet) {
                          ExplicitPrefixSet eps = (ExplicitPrefixSet) e;
                          Set<PrefixRange> ranges = eps.getPrefixSpace().getPrefixRanges();
                          for (PrefixRange r : ranges) {
                            acc.add(r.getPrefix());
                          }
                        }
                      }
                    }
                  }
                }
              }
            });
      }
      return acc;
    }

    if (proto.isConnected()) {
      conf.getInterfaces()
          .forEach(
              (name, iface) -> {
                Prefix p = iface.getPrefix();
                if (p != null) {
                  acc.add(p);
                }
              });
      return acc;
    }

    if (proto.isStatic()) {
      for (StaticRoute sr : conf.getDefaultVrf().getStaticRoutes()) {
        if (sr.getNetwork() != null && !Graph.isNullRouted(sr)) {
          acc.add(sr.getNetwork());
        }
      }
      return acc;
    }

    throw new BatfishException("ERROR: getOriginatedNetworks: " + proto.name());
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("=======================================================\n");
    sb.append("---------- Router to edges map ----------\n");
    _edgeMap.forEach(
        (router, graphEdges) -> {
          sb.append("Router: ").append(router).append("\n");
          graphEdges.forEach(
              edge -> {
                sb.append("  edge from: ").append(edge.getStart().getName());
                if (edge.getEnd() == null) {
                  sb.append(" to: null \n");
                } else {
                  sb.append(" to: ")
                      .append(edge.getPeer())
                      .append(",")
                      .append(edge.getEnd().getName())
                      .append("\n");
                }
              });
        });

    sb.append("---------------- eBGP Neighbors ----------------\n");
    _ebgpNeighbors.forEach(
        (ge, n) -> {
          sb.append("Edge: ").append(ge).append(" (").append(n.getAddress()).append(")\n");
        });

    sb.append("---------------- iBGP Neighbors ----------------\n");
    _ibgpNeighbors.forEach(
        (ge, n) -> {
          sb.append("Edge: ").append(ge).append(" (").append(n.getPrefix()).append(")\n");
        });

    sb.append("---------- Static Routes by Interface ----------\n");
    _staticRoutes.forEach(
        (router, map) -> {
          map.forEach(
              (iface, srs) -> {
                for (StaticRoute sr : srs) {
                  sb.append("Router: ")
                      .append(router)
                      .append(", Interface: ")
                      .append(iface)
                      .append(" --> ")
                      .append(sr.getNetwork())
                      .append("\n");
                }
              });
        });

    sb.append("=======================================================\n");
    return sb.toString();
  }

  /*
   * Getters and setters
   */

  public Map<String, String> getRouteReflectorParent() {
    return _routeReflectorParent;
  }

  public Map<String, Set<String>> getRouteReflectorClients() {
    return _routeReflectorClients;
  }

  public Map<String, Integer> getOriginatorId() {
    return _originatorId;
  }

  public Map<GraphEdge, BgpNeighbor> getEbgpNeighbors() {
    return _ebgpNeighbors;
  }

  public Map<GraphEdge, BgpNeighbor> getIbgpNeighbors() {
    return _ibgpNeighbors;
  }

  public Map<String, Map<String, List<StaticRoute>>> getStaticRoutes() {
    return _staticRoutes;
  }

  public Map<String, Set<Long>> getAreaIds() {
    return _areaIds;
  }

  public Map<String, Configuration> getConfigurations() {
    return _configurations;
  }

  public Map<String, Set<String>> getNeighbors() {
    return _neighbors;
  }

  public Map<String, List<GraphEdge>> getEdgeMap() {
    return _edgeMap;
  }

  public Map<GraphEdge, GraphEdge> getOtherEnd() {
    return _otherEnd;
  }

  public IBatfish getBatfish() {
    return _batfish;
  }
}
