package batfish.representation;

import batfish.representation.PolicyMapMatchType;
import batfish.representation.PolicyMapMatchLine;
import batfish.util.Util;

public class PolicyMapMatchNeighborLine extends PolicyMapMatchLine {

   private Ip _neighborIp;

   public PolicyMapMatchNeighborLine(Ip neighborIP) {
      _neighborIp = neighborIP;
   }

   @Override
   public PolicyMapMatchType getType() {
      return PolicyMapMatchType.NEIGHBOR;
   }

   public Ip getNeighborIp() {
      return _neighborIp;
   }
   
   @Override
   public String getIFString(int indentLevel) {
	   return Util.getIndentString(indentLevel) + "Neighbor " + _neighborIp;
   }
   @Override
   public boolean sameParseTree(PolicyMapMatchLine line, String prefix) {
      boolean res =  (line.getType() == PolicyMapMatchType.NEIGHBOR) && (_neighborIp.equals(((PolicyMapMatchNeighborLine) line)._neighborIp));
      if(res == false){
         System.out.println("PoliMapMatchNeighLine "+prefix);
      }
      return res;
   }

}