package batfish.representation.cisco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import batfish.representation.GeneratedRoute;
import batfish.representation.AsPathAccessList;
import batfish.representation.AsPathAccessListLine;
import batfish.representation.BgpNeighbor;
import batfish.representation.CommunityList;
import batfish.representation.CommunityListLine;
import batfish.representation.Configuration;
import batfish.representation.Ip;
import batfish.representation.IpAccessList;
import batfish.representation.LineAction;
import batfish.representation.IpAccessListLine;
import batfish.representation.OspfArea;
import batfish.representation.PolicyMap;
import batfish.representation.PolicyMapAction;
import batfish.representation.PolicyMapClause;
import batfish.representation.PolicyMapMatchAsPathAccessListLine;
import batfish.representation.PolicyMapMatchCommunityListLine;
import batfish.representation.PolicyMapMatchIpAccessListLine;
import batfish.representation.PolicyMapMatchLine;
import batfish.representation.PolicyMapMatchProtocolLine;
import batfish.representation.PolicyMapMatchRouteFilterListLine;
import batfish.representation.PolicyMapMatchType;
import batfish.representation.PolicyMapSetAddCommunityLine;
import batfish.representation.PolicyMapSetCommunityLine;
import batfish.representation.PolicyMapSetLine;
import batfish.representation.PolicyMapSetMetricLine;
import batfish.representation.Protocol;
import batfish.representation.RouteFilterLengthRangeLine;
import batfish.representation.RouteFilterLine;
import batfish.representation.RouteFilterList;
import batfish.representation.SwitchportEncapsulationType;
import batfish.representation.VendorConfiguration;
import batfish.representation.VendorConversionException;
import batfish.util.SubRange;
import batfish.util.Util;

public class CiscoVendorConfiguration implements VendorConfiguration {

   private static final String DEFAULT_ROUTE_FILTER_NAME = "~DEFAULT_ROUTE_FILTER~";
   private static final int MAX_ADMINISTRATIVE_COST = 32767;
   private static final String OSPF_EXPORT_CONNECTED_POLICY_NAME = "~OSPF_EXPORT_CONNECTED_POLICY~";
   private static final String OSPF_EXPORT_DEFAULT_POLICY_NAME = "~OSPF_EXPORT_DEFAULT_ROUTE_POLICY~";
   private static final String OSPF_EXPORT_STATIC_POLICY_NAME = "~OSPF_EXPORT_STATIC_POLICY~";
   private static final String OSPF_EXPORT_STATIC_REJECT_DEFAULT_ROUTE_FILTER_NAME = "~OSPF_EXPORT_STATIC_REJECT_DEFAULT_ROUTE_FILTER~";
   private static final String VENDOR_NAME = "cisco";

   private static PolicyMap makeRouteExportPolicy(Configuration c, String name,
         String prefixListName, String prefix, int prefixLength,
         SubRange prefixRange, LineAction prefixAction, Integer metric,
         Protocol protocol, PolicyMapAction policyAction) {
      Set<PolicyMapMatchLine> matchLines = new LinkedHashSet<PolicyMapMatchLine>();
      if (protocol != null) {
         PolicyMapMatchProtocolLine matchProtocolLine = new PolicyMapMatchProtocolLine(
               Collections.singletonList(protocol));
         matchLines.add(matchProtocolLine);
      }
      if (prefixListName != null) {
         RouteFilterList newRouteFilter = c.getRouteFilterLists().get(
               prefixListName);
         if (newRouteFilter == null) {
            newRouteFilter = makeRouteFilter(prefixListName, prefix,
                  prefixLength, prefixRange, prefixAction);
            c.getRouteFilterLists().put(newRouteFilter.getName(),
                  newRouteFilter);
         }
         PolicyMapMatchRouteFilterListLine matchRouteLine = new PolicyMapMatchRouteFilterListLine(
               Collections.singleton(newRouteFilter));
         matchLines.add(matchRouteLine);
      }
      Set<PolicyMapSetLine> setLines = new LinkedHashSet<PolicyMapSetLine>();
      if (metric != null) {
         PolicyMapSetMetricLine setMetricLine = new PolicyMapSetMetricLine(
               metric);
         setLines.add(setMetricLine);
      }
      PolicyMapClause clause = new PolicyMapClause(policyAction, "",
            matchLines, setLines);
      PolicyMap output = new PolicyMap(name, Collections.singletonList(clause));
      c.getPolicyMaps().put(output.getMapName(), output);
      return output;
   }

   private static RouteFilterList makeRouteFilter(String name, String prefix,
         int prefixLength, SubRange prefixRange, LineAction prefixAction) {
      RouteFilterList list = new RouteFilterList(name);
      RouteFilterLine line = new RouteFilterLengthRangeLine(prefixAction,
            new Ip(prefix), prefixLength, prefixRange);
      list.addLine(line);
      return list;
   }

   private static AsPathAccessList toAsPathAccessList(
         IpAsPathAccessList pathList) {
      String name = "" + pathList.getName();
      List<AsPathAccessListLine> lines = new ArrayList<AsPathAccessListLine>();
      for (IpAsPathAccessListLine fromLine : pathList.getLines()) {
         lines.add(new AsPathAccessListLine(fromLine.getRegex()));
      }
      return new AsPathAccessList(name, lines);
   }

   private static batfish.representation.BgpProcess toBgpProcess(
         final Configuration c, BgpProcess proc)
         throws VendorConversionException {
      batfish.representation.BgpProcess newBgpProcess = new batfish.representation.BgpProcess();
      Map<String, BgpNeighbor> newBgpNeighbors = newBgpProcess.getNeighbors();
      Set<String> activeNeighbors = proc.getActivatedNeighbors();
      int defaultMetric = proc.getDefaultMetric();

      Set<PolicyMap> globalExportPolicies = new HashSet<PolicyMap>();

      // add generated routes for aggregate addresses
      for (BgpNetwork aggNet : proc.getAggregateNetworks().keySet()) {
         boolean summaryOnly = proc.getAggregateNetworks().get(aggNet);
         String prefix = aggNet.getNetworkAddress();
         int prefixLength = Util.numSubnetBits(aggNet.getSubnetMask());
         SubRange prefixRange = new SubRange(prefixLength + 1, 32);
         LineAction prefixAction = LineAction.ACCEPT;
         String filterName = "~MATCH_SUMMARIZED_OF:" + prefix + "~";
         if (summaryOnly) {
            // we need to filter out more specific networks
            String policyName = "~DENY_SUMMARIZED_OF:" + prefix + "~";
            PolicyMap denySummarizedRoutes = makeRouteExportPolicy(c,
                  policyName, filterName, prefix, prefixLength, prefixRange,
                  prefixAction, null, null, PolicyMapAction.DENY);
            globalExportPolicies.add(denySummarizedRoutes);
         }

         // create generation policy for aggregate network
         String generationPolicyName = "~AGGREGATE_ROUTE_GEN:" + prefix + "~";
         PolicyMap generationPolicy = makeRouteExportPolicy(c,
               generationPolicyName, filterName, prefix, prefixLength,
               prefixRange, prefixAction, null, null, PolicyMapAction.PERMIT);
         Set<PolicyMap> generationPolicies = new HashSet<PolicyMap>();
         generationPolicies.add(generationPolicy);
         GeneratedRoute gr = new GeneratedRoute(new Ip(prefix), prefixLength,
               0, generationPolicies);
         newBgpProcess.getGeneratedRoutes().add(gr);
      }

      // create redistribution origination policies
      PolicyMap redistributeStaticPolicyMap = null;
      if (proc.getRedistributeStatic()) {
         redistributeStaticPolicyMap = makeRouteExportPolicy(c, "~BGP_REDISTRIBUTE_STATIC_ORIGINATION_POLICY~", null, null, 0, null, null, null, Protocol.STATIC, PolicyMapAction.PERMIT);
      }
      
      for (BgpPeerGroup pg : proc.getPeerGroups().values()) {
         // update source
         String updateSourceInterface = pg.getUpdateSource();
         String updateSource = null;
         if (updateSourceInterface == null) {
            updateSource = proc.getRouterId();
         }
         else {
            Ip sourceIp = c.getInterfaces().get(updateSourceInterface).getIP();
            updateSource = sourceIp.toString(); 
         }

         PolicyMap newInboundPolicyMap = null;
         String inboundRouteMapName = pg.getInboundRouteMap();
         if (inboundRouteMapName != null) {
            newInboundPolicyMap = c.getPolicyMaps().get(inboundRouteMapName);
            if (newInboundPolicyMap == null) {
               throw new VendorConversionException(
                     "undefined reference to inbound policy map: "
                           + inboundRouteMapName);
            }
         }
         PolicyMap newOutboundPolicyMap = null;
         String outboundRouteMapName = pg.getOutboundRouteMap();
         if (outboundRouteMapName != null) {
            newOutboundPolicyMap = c.getPolicyMaps().get(outboundRouteMapName);
            if (newOutboundPolicyMap == null) {
               throw new VendorConversionException(
                     "undefined reference to outbound policy map: "
                           + outboundRouteMapName);
            }
         }

         Set<PolicyMap> originationPolicies = new LinkedHashSet<PolicyMap>();
         // create origination prefilter from listed advertised networks
         RouteFilterList filter = new RouteFilterList("~BGP_PRE_FILTER:"
               + pg.getName() + "~");
         for (BgpNetwork network : proc.getNetworks()) {
            Ip netAdd = new Ip(network.getNetworkAddress());
            int prefixLen = Util.numSubnetBits(network.getSubnetMask());
            RouteFilterLengthRangeLine line = new RouteFilterLengthRangeLine(
                  LineAction.ACCEPT, netAdd, prefixLen, new SubRange(prefixLen,
                        prefixLen));
            filter.addLine(line);
         }
         c.getRouteFilterLists().put(filter.getName(), filter);

         // add prefilter policy for explicitly advertised networks
         List<PolicyMapClause> clauses = new ArrayList<PolicyMapClause>();
         Set<RouteFilterList> rfLines = new LinkedHashSet<RouteFilterList>();
         rfLines.add(filter);
         PolicyMapMatchRouteFilterListLine rfLine = new PolicyMapMatchRouteFilterListLine(
               rfLines);
         Set<PolicyMapMatchLine> matchLines = new LinkedHashSet<PolicyMapMatchLine>();
         matchLines.add(rfLine);
         Set<PolicyMapSetLine> setLines = new LinkedHashSet<PolicyMapSetLine>();
         PolicyMapClause clause = new PolicyMapClause(PolicyMapAction.PERMIT,
               "", matchLines, setLines);
         clauses.add(clause);
         PolicyMap explicitOriginationPolicyMap = new PolicyMap(
               "~BGP_ADVERTISED_NETWORKS_POLICY:" + pg.getName() + "~", clauses);
         c.getPolicyMaps().put(explicitOriginationPolicyMap.getMapName(),
               explicitOriginationPolicyMap);
         originationPolicies.add(explicitOriginationPolicyMap);

         // add redistribution origination policies
         if (proc.getRedistributeStatic()) {
            originationPolicies.add(redistributeStaticPolicyMap);
         }
         
         // set up default export policy for this peer group
         GeneratedRoute defaultRoute = null;
         PolicyMap defaultOriginationPolicy = null;
         if (pg.getDefaultOriginate()) {
            defaultRoute = new GeneratedRoute(new Ip("0.0.0.0"), 0,
                  MAX_ADMINISTRATIVE_COST, new LinkedHashSet<PolicyMap>());
            defaultOriginationPolicy = makeRouteExportPolicy(
                  c,
                  "~BGP_DEFAULT_ROUTE_ORIGINATION_POLICY:" + pg.getName() + "~",
                  "BGP_DEFAULT_ROUTE_ORIGINATION_FILTER:" + pg.getName() + "~",
                  "0.0.0.0", 0, new SubRange(0, 0), LineAction.ACCEPT, 0,
                  Protocol.AGGREGATE, PolicyMapAction.PERMIT);
            originationPolicies.add(defaultOriginationPolicy);
            String defaultOriginateMapName = pg.getDefaultOriginateMap();
            if (defaultOriginateMapName != null) { // originate contingent on
                                                   // generation policy
               PolicyMap defaultRouteGenerationPolicy = c.getPolicyMaps().get(
                     defaultOriginateMapName);
               if (defaultRouteGenerationPolicy == null) {
                  throw new VendorConversionException(
                        "undefined reference to generated route policy map: "
                              + defaultOriginateMapName);
               }
               defaultRoute.getGenerationPolicies().add(
                     defaultRouteGenerationPolicy);
            }
         }

         Long clusterId = pg.getClusterId();
         boolean routeReflectorClient = pg.getRouteReflectorClient();
         if (routeReflectorClient) {
            if (clusterId == null) {
               clusterId = Util.ipToLong(updateSource);
            }
         }
         boolean sendCommunity = pg.getSendCommunity();
         for (String neighborAddress : pg.getNeighborAddresses()) {
            if (activeNeighbors.contains(neighborAddress)) {
               if (neighborAddress.equals("169.232.12.157")) {
                  System.out.print("");
               }
               BgpNeighbor newNeighbor = newBgpNeighbors.get(neighborAddress);
               if (newNeighbor == null) {
                  newNeighbor = new BgpNeighbor(new Ip(neighborAddress));
                  newBgpNeighbors.put(neighborAddress, newNeighbor);
               }
               if (newInboundPolicyMap != null) {
                  newNeighbor.addInboundPolicyMap(newInboundPolicyMap);
               }
               if (newOutboundPolicyMap != null) {
                  newNeighbor.addOutboundPolicyMap(newOutboundPolicyMap);
                  for (PolicyMap map : globalExportPolicies) {
                     newNeighbor.addOutboundPolicyMap(map);
                  }
                  if (defaultOriginationPolicy != null) {
                     newNeighbor.addOutboundPolicyMap(defaultOriginationPolicy);
                  }
               }
               if (newNeighbor.getGroupName() == null) {
                  newNeighbor.setGroupName(pg.getName());
               }
               if (routeReflectorClient) {
                  newNeighbor.setClusterId(clusterId);
               }
               if (defaultRoute != null) {
                  newNeighbor.getGeneratedRoutes().add(defaultRoute);
               }
               if (newNeighbor.getRemoteAs() == null) {
                  newNeighbor.setRemoteAs(pg.getRemoteAS());
               }
               if (newNeighbor.getLocalAs() == null) {
                  newNeighbor.setLocalAs(proc.getPid());
               }
               if (newNeighbor.getUpdateSource() == null) {
                  newNeighbor.setUpdateSource(updateSource);
               }
               newNeighbor.getOriginationPolicies().addAll(originationPolicies);
               if (newNeighbor.getSendCommunity() == null) {
                  newNeighbor.setSendCommunity(sendCommunity);
               }
               if (newNeighbor.getDefaultMetric() == null) {
                  newNeighbor.setDefaultMetric(defaultMetric);
               }
            }
         }
      }
      return newBgpProcess;
   }

   private static CommunityList toCommunityList(ExpandedCommunityList ecList) {
      List<CommunityListLine> cllList = new ArrayList<CommunityListLine>();
      for (ExpandedCommunityListLine ecll : ecList.getLines()) {
         cllList.add(toCommunityListLine(ecll));
      }
      CommunityList cList = new CommunityList(ecList.getName(), cllList);
      return cList;
   }

   private static CommunityListLine toCommunityListLine(
         ExpandedCommunityListLine eclLine) {
      return new CommunityListLine(eclLine.getAction(),
            toJavaRegex(eclLine.getRegex()));
   }

   private static IpAccessList toIpAccessList(ExtendedAccessList eaList) {
      String name = eaList.getId();
      List<IpAccessListLine> lines = new ArrayList<IpAccessListLine>();
      for (ExtendedAccessListLine fromLine : eaList.getLines()) {
         lines.add(new IpAccessListLine(fromLine.getAction(), fromLine
               .getProtocol(), new Ip(fromLine.getSourceIP()), new Ip(fromLine
               .getSourceWildcard()), new Ip(fromLine.getDestinationIP()),
               new Ip(fromLine.getDestinationWildcard()), fromLine
                     .getSrcPortRanges(), fromLine.getDstPortRange()));
      }
      return new IpAccessList(name, lines);
   }

   private static String toJavaRegex(String ciscoRegex) {
      String underscoreReplacement = "(,|\\\\{|\\\\}|^|\\$| )";
      String output = ciscoRegex.replaceAll("_", underscoreReplacement);
      return output;
   }

   private static batfish.representation.OspfProcess toOspfProcess(
         Configuration c, OspfProcess proc) throws VendorConversionException {
      batfish.representation.OspfProcess newProcess = new batfish.representation.OspfProcess();

      // establish areas and associated interfaces
      HashMap<Integer, OspfArea> areas = newProcess.getAreas();
      List<OspfNetwork> networks = proc.getNetworks();
      Collections.sort(networks, new Comparator<OspfNetwork>() {
         // sort so longest prefixes are first
         @Override
         public int compare(OspfNetwork lhs, OspfNetwork rhs) {
            int lhsPrefixLength = Util.numSubnetBits(lhs.getSubnetMask());
            int rhsPrefixLength = Util.numSubnetBits(rhs.getSubnetMask());
            int result = -Integer.compare(lhsPrefixLength, rhsPrefixLength);
            if (result == 0) {
               long lhsIp = Util.ipToLong(lhs.getNetworkAddress());
               long rhsIp = Util.ipToLong(rhs.getNetworkAddress());
               result = Long.compare(lhsIp, rhsIp);
            }
            return result;
         }
      });
      for (batfish.representation.Interface i : c.getInterfaces().values()) {
         Ip interfaceIp = i.getIP();
         if (interfaceIp == null) {
            continue;
         }
         for (OspfNetwork network : networks) {
            Ip networkIp = new Ip(network.getNetworkAddress());
            Ip networkMask = new Ip(network.getSubnetMask());
            long maskedIp = interfaceIp.asLong() & networkMask.asLong();
            if (maskedIp == networkIp.asLong()) {
               // we have a longest prefix match
               int areaNum = network.getArea();
               OspfArea newArea = areas.get(areaNum);
               if (newArea == null) {
                  newArea = new OspfArea(areaNum);
                  areas.put(areaNum, newArea);
               }
               newArea.getInterfaces().add(i);
               break;
            }
         }
      }

      // policy map for default information
      if (proc.getDefaultInformationOriginate()) {
         String defaultPrefix = "0.0.0.0";
         int defaultPrefixLength = 0;
         SubRange defaultPrefixRange = new SubRange(0, 0);
         int metric = proc.getDefaultInformationMetric();
         // add default export map with metric
         PolicyMap exportDefaultPolicy;
         String mapName = proc.getDefaultInformationOriginateMap();
         Set<PolicyMap> generationPolicies = new LinkedHashSet<PolicyMap>();
         if (mapName != null) {
            PolicyMap generationPolicy = c.getPolicyMaps().get(mapName);
            if (generationPolicy == null) {
               throw new VendorConversionException(
                     "undefined reference to generation policy map: " + mapName);
            }
            else {
               exportDefaultPolicy = makeRouteExportPolicy(c,
                     OSPF_EXPORT_DEFAULT_POLICY_NAME,
                     DEFAULT_ROUTE_FILTER_NAME, defaultPrefix,
                     defaultPrefixLength, defaultPrefixRange,
                     LineAction.ACCEPT, metric, Protocol.AGGREGATE,
                     PolicyMapAction.PERMIT);
               newProcess.getOutboundPolicyMaps().add(exportDefaultPolicy);
               generationPolicies.add(generationPolicy);
               GeneratedRoute route = new GeneratedRoute(new Ip(defaultPrefix),
                     defaultPrefixLength, MAX_ADMINISTRATIVE_COST,
                     generationPolicies);
               newProcess.getGeneratedRoutes().add(route);
            }
         }
         else if (proc.getDefaultInformationOriginateAlways()) {
            // add generated aggregate with no precondition
            exportDefaultPolicy = makeRouteExportPolicy(c,
                  OSPF_EXPORT_DEFAULT_POLICY_NAME, DEFAULT_ROUTE_FILTER_NAME,
                  defaultPrefix, defaultPrefixLength, defaultPrefixRange,
                  LineAction.ACCEPT, metric, Protocol.AGGREGATE,
                  PolicyMapAction.PERMIT);
            c.getPolicyMaps().put(exportDefaultPolicy.getMapName(),
                  exportDefaultPolicy);
            newProcess.getOutboundPolicyMaps().add(exportDefaultPolicy);
            GeneratedRoute route = new GeneratedRoute(new Ip(defaultPrefix),
                  defaultPrefixLength, MAX_ADMINISTRATIVE_COST, null);
            newProcess.getGeneratedRoutes().add(route);
         }
         else {
            // do not generate an aggregate default route;
            // just redistribute any existing default route with the new metric
            exportDefaultPolicy = makeRouteExportPolicy(c,
                  OSPF_EXPORT_DEFAULT_POLICY_NAME, DEFAULT_ROUTE_FILTER_NAME,
                  defaultPrefix, defaultPrefixLength, defaultPrefixRange,
                  LineAction.ACCEPT, metric, null, PolicyMapAction.PERMIT);
            c.getPolicyMaps().put(exportDefaultPolicy.getMapName(),
                  exportDefaultPolicy);
            newProcess.getOutboundPolicyMaps().add(exportDefaultPolicy);
         }
      }

      // policy map for redistributing connected routes
      // TODO: honor subnets option
      if (proc.getRedistributeConnected()) {
         int metric = proc.getRedistributeConnectedMetric();
         // add default export map with metric
         PolicyMap exportConnectedPolicy;
         String mapName = proc.getRedistributeConnectedMap();
         if (mapName != null) {
            exportConnectedPolicy = c.getPolicyMaps().get(mapName);
            if (exportConnectedPolicy == null) {
               throw new VendorConversionException(
                     "undefined reference to policy map: " + mapName);
            }
            PolicyMapMatchLine matchConnectedLine = new PolicyMapMatchProtocolLine(
                  Collections.singletonList(Protocol.CONNECTED));
            PolicyMapSetLine setMetricLine = new PolicyMapSetMetricLine(metric);
            for (PolicyMapClause clause : exportConnectedPolicy.getClauses()) {
               clause.getMatchLines().add(matchConnectedLine);
               clause.getSetLines().add(setMetricLine);
            }
            newProcess.getOutboundPolicyMaps().add(exportConnectedPolicy);

         }
         else {
            exportConnectedPolicy = makeRouteExportPolicy(c,
                  OSPF_EXPORT_CONNECTED_POLICY_NAME, null, null, 0, null, null,
                  metric, Protocol.CONNECTED, PolicyMapAction.PERMIT);
            newProcess.getOutboundPolicyMaps().add(exportConnectedPolicy);
            c.getPolicyMaps().put(exportConnectedPolicy.getMapName(),
                  exportConnectedPolicy);
         }
      }

      // policy map for redistributing static routes
      // TODO: honor subnets option
      if (proc.getRedistributeStatic()) {
         int metric = proc.getRedistributeStaticMetric();
         // add export map with metric
         PolicyMap exportStaticPolicy;
         String mapName = proc.getRedistributeStaticMap();
         if (mapName != null) {
            exportStaticPolicy = c.getPolicyMaps().get(mapName);
            if (exportStaticPolicy != null) { // assume for now that all maps
                                              // have prefix matching
               PolicyMapMatchLine matchStaticLine = new PolicyMapMatchProtocolLine(
                     Collections.singletonList(Protocol.STATIC));
               PolicyMapSetLine setMetricLine = new PolicyMapSetMetricLine(
                     metric);
               for (PolicyMapClause clause : exportStaticPolicy.getClauses()) {
                  boolean containsRouteFilterList = false;
                  for (PolicyMapMatchLine matchLine : clause.getMatchLines()) {
                     if (matchLine.getType() == PolicyMapMatchType.ROUTE_FILTER_LIST) {
                        PolicyMapMatchRouteFilterListLine rLine = (PolicyMapMatchRouteFilterListLine) matchLine;
                        for (RouteFilterList list : rLine.getLists()) {
                           containsRouteFilterList = true;
                           list.getLines().add(
                                 0,
                                 new RouteFilterLengthRangeLine(
                                       LineAction.REJECT, new Ip("0.0.0.0"), 0,
                                       new SubRange(0, 0)));
                        }
                     }
                     else {
                        // note: don't allow ip access lists in policies that
                        // are for prefix matching
                        // i.e. convert them, or throw error if they are used
                        // ambiguously
                        throw new Error("Unexpected match line type");
                     }
                  }
                  if (!containsRouteFilterList) {
                     throw new Error(
                           "Expected at least one route filter match in this clause");
                  }
                  Set<PolicyMapSetLine> setList = clause.getSetLines();
                  if (setList.size() > 0) {
                     throw new Error("Expected no set lines here");
                  }
                  clause.getMatchLines().add(matchStaticLine);
                  setList.add(setMetricLine);
               }
               newProcess.getOutboundPolicyMaps().add(exportStaticPolicy);
            }
            else { // bad policy name
               throw new Error("bad policy name");
            }
         }
         else { // export static routes without named policy
            exportStaticPolicy = makeRouteExportPolicy(c,
                  OSPF_EXPORT_STATIC_POLICY_NAME,
                  OSPF_EXPORT_STATIC_REJECT_DEFAULT_ROUTE_FILTER_NAME,
                  "0.0.0.0", 0, new SubRange(0, 0), LineAction.REJECT, metric,
                  Protocol.STATIC, PolicyMapAction.PERMIT);
            newProcess.getOutboundPolicyMaps().add(exportStaticPolicy);
         }
      }
      newProcess.setReferenceBandwidth(proc.getReferenceBandwidth());
      newProcess.setRouterId(proc.getRouterId());
      return newProcess;
   }

   private static PolicyMap toPolicyMap(final Configuration c, RouteMap map)
         throws VendorConversionException {
      List<PolicyMapClause> clauses = new ArrayList<PolicyMapClause>();
      for (RouteMapClause rmClause : map.getClauseList()) {
         clauses.add(toPolicyMapClause(c, rmClause));
      }
      return new PolicyMap(map.getMapName(), clauses);
   }

   private static PolicyMapClause toPolicyMapClause(final Configuration c,
         RouteMapClause clause) throws VendorConversionException {
      Set<PolicyMapMatchLine> matchLines = new LinkedHashSet<PolicyMapMatchLine>();
      for (RouteMapMatchLine rmMatchLine : clause.getMatchList()) {
         matchLines.add(toPolicyMapMatchLine(c, rmMatchLine));
      }
      Set<PolicyMapSetLine> setLines = new LinkedHashSet<PolicyMapSetLine>();
      for (RouteMapSetLine rmSetLine : clause.getSetList()) {
         setLines.add(rmSetLine.toPolicyMapSetLine(c));
      }
      return new PolicyMapClause(PolicyMapAction.fromLineAction(clause
            .getAction()), Integer.toString(clause.getSeqNum()), matchLines,
            setLines);
   }

   private static PolicyMapMatchLine toPolicyMapMatchLine(
         final Configuration c, RouteMapMatchLine matchLine)
         throws VendorConversionException {
      PolicyMapMatchLine newLine = null;
      switch (matchLine.getType()) {
      case AS_PATH_ACCESS_LIST:
         RouteMapMatchAsPathAccessListLine pathLine = (RouteMapMatchAsPathAccessListLine) matchLine;
         Set<AsPathAccessList> newAsPathMatchSet = new LinkedHashSet<AsPathAccessList>();
         for (String pathListName : pathLine.getListNames()) {
            AsPathAccessList list = c.getAsPathAccessLists().get(pathListName);
            if (list == null) {
               throw new Error("null list");
            }
            newAsPathMatchSet.add(list);
         }
         newLine = new PolicyMapMatchAsPathAccessListLine(newAsPathMatchSet);
         break;

      case COMMUNITY_LIST:
         RouteMapMatchCommunityListLine communityLine = (RouteMapMatchCommunityListLine) matchLine;
         Set<CommunityList> newCommunityMatchSet = new LinkedHashSet<CommunityList>();
         for (String listName : communityLine.getListNames()) {
            CommunityList list = c.getCommunityLists().get(listName);
            if (list == null) {
               throw new VendorConversionException(
                     "Reference to nonexistent community list: " + listName);
            }
            newCommunityMatchSet.add(list);
         }
         newLine = new PolicyMapMatchCommunityListLine(newCommunityMatchSet);
         break;

      case IP_ACCESS_LIST:
         RouteMapMatchIpAccessListLine accessLine = (RouteMapMatchIpAccessListLine) matchLine;
         Set<IpAccessList> newIpAccessMatchSet = new LinkedHashSet<IpAccessList>();
         for (String listName : accessLine.getListNames()) {
            IpAccessList list = c.getIpAccessLists().get(listName);
            newIpAccessMatchSet.add(list);
         }
         newLine = new PolicyMapMatchIpAccessListLine(newIpAccessMatchSet);
         break;

      case IP_PREFIX_LIST:
         RouteMapMatchIpPrefixListLine prefixLine = (RouteMapMatchIpPrefixListLine) matchLine;
         Set<RouteFilterList> newRouteFilterMatchSet = new LinkedHashSet<RouteFilterList>();
         for (String prefixListName : prefixLine.getListNames()) {
            RouteFilterList list = c.getRouteFilterLists().get(prefixListName);
            if (list == null) {
               throw new VendorConversionException(
                     "undefined reference to route filter list: "
                           + prefixListName);
            }
            newRouteFilterMatchSet.add(list);
         }
         newLine = new PolicyMapMatchRouteFilterListLine(newRouteFilterMatchSet);
         break;

      case NEIGHBOR:
         // TODO: implement
         break;

      case PROTOCOL:
         // TODO: implement
         break;

      default:
         throw new Error("bad type");
      }
      return newLine;
   }

   private static RouteFilterLine toRouteFilterLine(
         ExtendedAccessListLine fromLine) {
      LineAction action = fromLine.getAction();
      Ip prefix = new Ip(fromLine.getSourceIP());
      long prefixSubnet = ~(Util.ipToLong(fromLine.getSourceWildcard())) & 0xFFFFFFFF;
      int prefixLength = Util.numSubnetBits(Util.longToIp(prefixSubnet));
      long minSubnet = Util.ipToLong(fromLine.getDestinationIP());
      long maxSubnet = minSubnet
            | Util.ipToLong(fromLine.getDestinationWildcard());
      int minPrefixLength = Util.numSubnetBits(fromLine.getDestinationIP());
      int maxPrefixLength = Util.numSubnetBits(Util.longToIp(maxSubnet));
      return new RouteFilterLengthRangeLine(action, prefix, prefixLength,
            new SubRange(minPrefixLength, maxPrefixLength));
   }

   private static RouteFilterList toRouteFilterList(ExtendedAccessList eaList) {
      String name = eaList.getId();
      RouteFilterList newList = new RouteFilterList(name);
      List<RouteFilterLine> lines = new ArrayList<RouteFilterLine>();
      for (ExtendedAccessListLine fromLine : eaList.getLines()) {
         RouteFilterLine newLine = toRouteFilterLine(fromLine);
         lines.add(newLine);
      }
      newList.addLines(lines);
      return newList;

   }

   private static RouteFilterList toRouteFilterList(PrefixList list) {
      RouteFilterList newRouteFilterList = new RouteFilterList(list.getName());
      for (PrefixListLine prefixListLine : list.getLines()) {
         RouteFilterLine newRouteFilterListLine = new RouteFilterLengthRangeLine(
               prefixListLine.getAction(), new Ip(prefixListLine.getPrefix()),
               prefixListLine.getPrefixLength(),
               prefixListLine.getLengthRange());
         newRouteFilterList.addLine(newRouteFilterListLine);
      }
      return newRouteFilterList;
   }

   private static batfish.representation.StaticRoute toStaticRoute(
         Configuration c, StaticRoute staticRoute) {
      String nextHopIpStr = staticRoute.getNextHopIp();
      Ip nextHopIp = null;
      if (nextHopIpStr != null) {
         nextHopIp = new Ip(nextHopIpStr);
      }
      Ip prefix = new Ip(staticRoute.getPrefix());
      String nextHopInterface = staticRoute.getNextHopInterface();
      int prefixLength = Util.numSubnetBits(staticRoute.getMask());
      return new batfish.representation.StaticRoute(prefix, prefixLength,
            nextHopIp, nextHopInterface, staticRoute.getDistance());

   }

   private Map<String, IpAsPathAccessList> _asPathAccessLists;
   private BgpProcess _bgpProcess;
   private List<String> _conversionWarnings;
   private Map<String, ExpandedCommunityList> _expandedCommunityLists;
   private Map<String, ExtendedAccessList> _extendedAccessLists;
   private String _hostname;
   private List<Interface> _interfaces;
   private List<OspfProcess> _ospfProcesses;
   private Map<String, PrefixList> _prefixLists;

   private Map<String, RouteMap> _routeMaps;

   private Map<String, StandardAccessList> _standardAccessLists;

   private Map<String, StandardCommunityList> _standardCommunityLists;

   private List<StaticRoute> _staticRoutes;

   public CiscoVendorConfiguration() {
      _conversionWarnings = new ArrayList<String>();
      _ospfProcesses = new ArrayList<OspfProcess>();
      _interfaces = new ArrayList<Interface>();
      _standardAccessLists = new HashMap<String, StandardAccessList>();
      _extendedAccessLists = new HashMap<String, ExtendedAccessList>();
      _asPathAccessLists = new HashMap<String, IpAsPathAccessList>();
      _staticRoutes = new ArrayList<StaticRoute>();
      _bgpProcess = null;
      _routeMaps = new HashMap<String, RouteMap>();
      _prefixLists = new HashMap<String, PrefixList>();
      _standardCommunityLists = new HashMap<String, StandardCommunityList>();
      _expandedCommunityLists = new HashMap<String, ExpandedCommunityList>();
   }

   public void addAccessListLine(String id, StandardAccessListLine line) {
      if (!_standardAccessLists.containsKey(id)) {
         _standardAccessLists.put(id, new StandardAccessList(id));
      }
      _standardAccessLists.get(id).addLine(line);
   }

   public void addAsPathAccessListLine(String name, IpAsPathAccessListLine line) {
      if (!_asPathAccessLists.containsKey(name)) {
         _asPathAccessLists.put(name, new IpAsPathAccessList(name));
      }
      _asPathAccessLists.get(name).addLine(line);
   }

   public void addExpandedCommunityListLine(String name,
         ExpandedCommunityListLine line) {
      ExpandedCommunityList cl = _expandedCommunityLists.get(name);
      if (cl == null) {
         cl = new ExpandedCommunityList(name);
         _expandedCommunityLists.put(name, cl);
      }
      cl.addLine(line);
   }

   public void addExpandedCommunityLists(List<ExpandedCommunityList> lists) {
      for (ExpandedCommunityList ecl : lists) {
         ExpandedCommunityList cl = _expandedCommunityLists.get(ecl.getName());
         if (cl == null) {
            _expandedCommunityLists.put(ecl.getName(), ecl);
         }
         else {
            throw new Error("duplicate community lists");
         }

      }
   }

   public void addExtendedAccessList(ExtendedAccessList eal) {
      if (_extendedAccessLists.containsKey(eal.getId())) {
         throw new Error("duplicate extended access list name");
      }
      _extendedAccessLists.put(eal.getId(), eal);
   }

   public void addExtendedAccessListLine(String id, ExtendedAccessListLine eall) {
      if (!_extendedAccessLists.containsKey(id)) {
         _extendedAccessLists.put(id, new ExtendedAccessList(id));
      }
      _extendedAccessLists.get(id).addLine(eall);
   }

   public void addInterface(Interface interface1) {
      _interfaces.add(interface1);
   }

   public void addOspfProcess(OspfProcess process) {
      if (_ospfProcesses.size() > 0) {
         throw new Error(
               "More than one OSPF process not supported at this time");
      }
      _ospfProcesses.add(process);
   }

   public void addPrefixListLine(String prefixListName, PrefixListLine line) {
      PrefixList list = _prefixLists.get(prefixListName);
      if (list == null) {
         list = new PrefixList(prefixListName);
         _prefixLists.put(prefixListName, list);
      }
      list.addLine(line);
   }

   public void addRouteFilters(List<PrefixList> fl) {
      for (PrefixList rf : fl) {
         _prefixLists.put(rf.getName(), rf);
      }
   }

   public void addRouteMapClause(RouteMapClause clause) {
      RouteMap rm = null;
      if (_routeMaps.containsKey(clause.getMapName())) {
         rm = _routeMaps.get(clause.getMapName());
         rm.addClause(clause);
      }
      else {
         rm = new RouteMap(clause.getMapName());
         rm.addClause(clause);
         _routeMaps.put(rm.getMapName(), rm);
      }
      rm.setIgnore(rm.getIgnore() || clause.getIgnore());
   }

   public void addRouteMaps(List<RouteMap> ml) {
      for (RouteMap rm : ml) {
         _routeMaps.put(rm.getMapName(), rm);
      }
   }

   public void addStandardCommunityListLine(String name,
         StandardCommunityListLine line) {
      StandardCommunityList cl = _standardCommunityLists.get(name);
      if (cl == null) {
         cl = new StandardCommunityList(name);
         _standardCommunityLists.put(name, cl);
      }
      cl.addLine(line);
   }

   public void addStaticRoute(StaticRoute staticRoute) {
      _staticRoutes.add(staticRoute);
   }

   public void addStaticRoutes(List<StaticRoute> staticRoutes) {
      _staticRoutes.addAll(staticRoutes);
   }

   private boolean containsIpAccessList(String eaListName, String mapName) {
      if (mapName != null) {
         RouteMap currentMap = _routeMaps.get(mapName);
         if (currentMap == null) {
            throw new Error("undefined reference to routemap: " + mapName);
         }
         for (RouteMapClause clause : currentMap.getClauseList()) {
            for (RouteMapMatchLine matchLine : clause.getMatchList()) {
               if (matchLine.getType() == RouteMapMatchType.IP_ACCESS_LIST) {
                  RouteMapMatchIpAccessListLine ipall = (RouteMapMatchIpAccessListLine) matchLine;
                  for (String listName : ipall.getListNames()) {
                     if (eaListName.equals(listName)) {
                        return true;
                     }
                  }
               }
            }
         }
      }
      return false;
   }

   private void convertForPurpose(Set<RouteMap> routingRouteMaps, RouteMap map) {
      if (routingRouteMaps.contains(map)) {
         for (RouteMapClause clause : map.getClauseList()) {
            List<RouteMapMatchLine> matchList = clause.getMatchList();
            for (int i = 0; i < matchList.size(); i++) {
               RouteMapMatchLine line = matchList.get(i);
               if (line.getType() == RouteMapMatchType.IP_ACCESS_LIST) {
                  RouteMapMatchIpAccessListLine oldLine = (RouteMapMatchIpAccessListLine) line;
                  matchList.remove(i);
                  RouteMapMatchIpPrefixListLine newLine = new RouteMapMatchIpPrefixListLine(
                        oldLine.getListNames());
                  matchList.add(newLine);
               }
            }
         }
      }
   }

   public Map<String, StandardAccessList> getAccessLists() {
      return _standardAccessLists;
   }

   public Map<String, IpAsPathAccessList> getAsPathAccessLists() {
      return _asPathAccessLists;
   }

   public BgpProcess getBgpProcess() {
      return _bgpProcess;
   }

   @Override
   public List<String> getConversionWarnings() {
      return _conversionWarnings;
   }

   public String getHostname() {
      return _hostname;
   }

   public List<Interface> getInterfaces() {
      return _interfaces;
   }

   public List<OspfProcess> getOspfProcesses() {
      return _ospfProcesses;
   }

   public Map<String, PrefixList> getRouteFilter() {
      return _prefixLists;
   }

   public Map<String, RouteMap> getRouteMaps() {
      return _routeMaps;
   }

   private Set<RouteMap> getRoutingRouteMaps() {
      Set<RouteMap> maps = new LinkedHashSet<RouteMap>();
      String currentMapName;
      RouteMap currentMap;
      // check ospf policies
      if (_ospfProcesses.size() > 0) {
         OspfProcess oproc = _ospfProcesses.get(0);
         currentMapName = oproc.getRedistributeConnectedMap();
         if (currentMapName != null) {
            currentMap = _routeMaps.get(currentMapName);
            if (currentMap != null) {
               maps.add(currentMap);
            }
         }
         currentMapName = oproc.getRedistributeStaticMap();
         if (currentMapName != null) {
            currentMap = _routeMaps.get(currentMapName);
            if (currentMap != null) {
               maps.add(currentMap);
            }
         }
         currentMapName = oproc.getDefaultInformationOriginateMap();
         if (currentMapName != null) {
            currentMap = _routeMaps.get(currentMapName);
            if (currentMap != null) {
               maps.add(currentMap);
            }
         }
      }
      // check bgp policies
      if (_bgpProcess != null) {
         for (BgpPeerGroup pg : _bgpProcess.getPeerGroups().values()) {
            currentMapName = pg.getInboundRouteMap();
            if (currentMapName != null) {
               currentMap = _routeMaps.get(currentMapName);
               if (currentMap != null) {
                  maps.add(currentMap);
               }
            }
            currentMapName = pg.getOutboundRouteMap();
            if (currentMapName != null) {
               currentMap = _routeMaps.get(currentMapName);
               if (currentMap != null) {
                  maps.add(currentMap);
               }
            }
         }
      }
      return maps;
   }

   public List<StaticRoute> getStaticRoutes() {
      return _staticRoutes;
   }

   public void setBgpProcess(BgpProcess process) {
      _bgpProcess = process;
   }

   public void setHostname(String hostname) {
      _hostname = hostname;
   }

   private batfish.representation.Interface toInterface(Interface iface,
         Map<String, IpAccessList> ipAccessLists)
         throws VendorConversionException {
      batfish.representation.Interface newIface = new batfish.representation.Interface(
            iface.getName());
      newIface.setAccessVlan(iface.getAccessVlan());
      newIface.setActive(iface.getActive());
      newIface.setArea(iface.getArea());
      newIface.setBandwidth(iface.getBandwidth());
      if (iface.getIP() != null) {
         newIface.setIP(new Ip(iface.getIP()));
         newIface.setSubnetMask(new Ip(iface.getSubnetMask()));
      }
      Map<String, String> secondaryIps = iface.getSecondaryIps();
      for (String ip : secondaryIps.keySet()) {
         String subnet = secondaryIps.get(ip);
         newIface.getSecondaryIps().put(new Ip(ip), new Ip(subnet));
      }
      newIface.setNativeVlan(iface.getNativeVlan());
      newIface.setOspfCost(iface.getOspfCost());
      newIface.setOspfDeadInterval(iface.getOspfDeadInterval());
      newIface.setOspfHelloMultiplier(iface.getOspfHelloMultiplier());
      newIface.setSwitchportMode(iface.getSwitchportMode());
      SwitchportEncapsulationType encapsulation = iface
            .getSwitchportTrunkEncapsulation();
      if (encapsulation == null) { // no encapsulation set, so use default..
                                   // TODO: check if this is OK
         encapsulation = SwitchportEncapsulationType.DOT1Q;
      }
      newIface.setSwitchportTrunkEncapsulation(encapsulation);
      String incomingFilterName = iface.getIncomingFilter();
      if (incomingFilterName != null) {
         IpAccessList incomingFilter = ipAccessLists.get(incomingFilterName);
         if (incomingFilter == null) {
            _conversionWarnings.add("Interface: '" + iface.getName()
                  + "' configured with non-existent incoming acl '"
                  + incomingFilterName + "'");
         }
         newIface.setIncomingFilter(incomingFilter);
      }
      String outgoingFilterName = iface.getOutgoingFilter();
      if (outgoingFilterName != null) {
         IpAccessList outgoingFilter = ipAccessLists.get(outgoingFilterName);
         if (outgoingFilter == null) {
            _conversionWarnings.add("Interface: '" + iface.getName()
                  + "' configured with non-existent outgoing acl '"
                  + outgoingFilterName + "'");
         }
         newIface.setOutgoingFilter(outgoingFilter);
      }
      return newIface;
   }

   @Override
   public Configuration toVendorIndependentConfiguration()
         throws VendorConversionException {
      final Configuration c = new Configuration(_hostname);
      c.setVendor(VENDOR_NAME);

      // convert as path access lists to vendor independent format
      for (IpAsPathAccessList pathList : _asPathAccessLists.values()) {
         AsPathAccessList apList = toAsPathAccessList(pathList);
         c.getAsPathAccessLists().put(apList.getName(), apList);
      }

      // convert standard/expanded community lists to community lists
      for (StandardCommunityList scList : _standardCommunityLists.values()) {
         ExpandedCommunityList ecList = scList.toExpandedCommunityList();
         CommunityList cList = toCommunityList(ecList);
         c.getCommunityLists().put(cList.getName(), cList);
      }
      for (ExpandedCommunityList ecList : _expandedCommunityLists.values()) {
         CommunityList cList = toCommunityList(ecList);
         c.getCommunityLists().put(cList.getName(), cList);
      }

      // convert prefix lists to route filter lists
      for (PrefixList prefixList : _prefixLists.values()) {
         RouteFilterList newRouteFilterList = toRouteFilterList(prefixList);
         c.getRouteFilterLists().put(newRouteFilterList.getName(),
               newRouteFilterList);
      }

      // convert standard/extended access lists to access lists or route filter
      // lists
      for (StandardAccessList saList : _standardAccessLists.values()) {
         ExtendedAccessList eaList = saList.toExtendedAccessList();
         if (usedForRouting(eaList)) {
            RouteFilterList rfList = toRouteFilterList(eaList);
            c.getRouteFilterLists().put(rfList.getName(), rfList);
         }
         else {
            IpAccessList ipaList = toIpAccessList(eaList);
            c.getIpAccessLists().put(ipaList.getName(), ipaList);
         }
      }
      for (ExtendedAccessList eaList : _extendedAccessLists.values()) {
         if (usedForRouting(eaList)) {
            RouteFilterList rfList = toRouteFilterList(eaList);
            c.getRouteFilterLists().put(rfList.getName(), rfList);
         }
         else {
            IpAccessList ipaList = toIpAccessList(eaList);
            c.getIpAccessLists().put(ipaList.getName(), ipaList);
         }
      }

      // convert route maps to policy maps
      Set<RouteMap> routingRouteMaps = getRoutingRouteMaps();
      for (RouteMap map : _routeMaps.values()) {
         if (map.getIgnore()) {
            continue;
         }
         //TODO: replace UCLA-SPECIFIC code
         if (map.getMapName().toLowerCase().endsWith("ipv6")) {
            continue;
         }
         
         convertForPurpose(routingRouteMaps, map);
         PolicyMap newMap = toPolicyMap(c, map);
         c.getPolicyMaps().put(newMap.getMapName(), newMap);
      }

      // convert interfaces
      for (Interface iface : _interfaces) {
         batfish.representation.Interface newInterface = toInterface(
               iface, c.getIpAccessLists());
         c.getInterfaces().put(newInterface.getName(), newInterface);
      }

      // convert static routes
      for (StaticRoute staticRoute : _staticRoutes) {
         c.getStaticRoutes().add(toStaticRoute(c, staticRoute));
      }

      // convert ospf process
      if (_ospfProcesses.size() > 0) {
         OspfProcess firstOspfProcess = _ospfProcesses.get(0);
         batfish.representation.OspfProcess newOspfProcess = toOspfProcess(
               c, firstOspfProcess);
         c.setOspfProcess(newOspfProcess);
      }

      // convert bgp process
      if (_bgpProcess != null) {
         batfish.representation.BgpProcess newBgpProcess = toBgpProcess(c,
               _bgpProcess);
         c.setBgpProcess(newBgpProcess);
      }

      // get all set and added communities
      for (PolicyMap map : c.getPolicyMaps().values()) {
         for (PolicyMapClause clause : map.getClauses()) {
            for (PolicyMapSetLine setLine : clause.getSetLines()) {
               switch (setLine.getType()) {
               case ADDITIVE_COMMUNITY:
                  PolicyMapSetAddCommunityLine sacLine = (PolicyMapSetAddCommunityLine) setLine;
                  c.getCommunities().addAll(sacLine.getCommunities());
                  break;
               case COMMUNITY:
                  PolicyMapSetCommunityLine scLine = (PolicyMapSetCommunityLine) setLine;
                  c.getCommunities().addAll(scLine.getCommunities());
                  break;
               case DELETE_COMMUNITY:
               case LOCAL_PREFERENCE:
               case METRIC:
               case NEXT_HOP:
                  break;
               default:
                  throw new Error("bad set type");
               }
            }
         }
      }

      return c;
   }

   private boolean usedForRouting(ExtendedAccessList eaList) {
      String eaListName = eaList.getId();
      String currentMapName;
      // check ospf policies
      if (_ospfProcesses.size() > 0) {
         OspfProcess oproc = _ospfProcesses.get(0);
         currentMapName = oproc.getRedistributeConnectedMap();
         if (containsIpAccessList(eaListName, currentMapName)) {
            return true;
         }
         currentMapName = oproc.getRedistributeStaticMap();
         if (containsIpAccessList(eaListName, currentMapName)) {
            return true;
         }
         currentMapName = oproc.getDefaultInformationOriginateMap();
         if (containsIpAccessList(eaListName, currentMapName)) {
            return true;
         }
      }
      // check bgp policies
      if (_bgpProcess != null) {
         for (BgpPeerGroup pg : _bgpProcess.getPeerGroups().values()) {
            currentMapName = pg.getInboundRouteMap();
            if (containsIpAccessList(eaListName, currentMapName)) {
               return true;
            }
            currentMapName = pg.getOutboundRouteMap();
            if (containsIpAccessList(eaListName, currentMapName)) {
               return true;
            }
         }
      }
      return false;
   }

}