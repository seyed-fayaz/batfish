package batfish.representation;

import java.util.List;

import batfish.representation.PolicyMapMatchType;
import batfish.representation.PolicyMapMatchLine;
import batfish.util.Util;

public class PolicyMapMatchProtocolLine extends PolicyMapMatchLine {

   private List<Protocol> _protocol;

   public PolicyMapMatchProtocolLine(List<Protocol> protocols) {
      _protocol = protocols;
   }

   @Override
   public PolicyMapMatchType getType() {
      return PolicyMapMatchType.PROTOCOL;
   }

   public List<Protocol> getProtocols() {
      return _protocol;
   }

   @Override
   public boolean sameParseTree(PolicyMapMatchLine line, String prefix) {
      boolean res = (line.getType() == PolicyMapMatchType.PROTOCOL);
      boolean finalRes = res;
      if(res == false){
         System.out.println("PoliMapMatchProtLine:Type "+prefix);
         return res;
      }
      
      PolicyMapMatchProtocolLine protLine = (PolicyMapMatchProtocolLine) line;
      if(_protocol.size() != protLine._protocol.size()){
         System.out.println("PoliMapMatchProtLine:Size "+prefix);
         return false;
      }else{
         for(int i=0; i< _protocol.size(); i++){
            res = (_protocol.get(i) == protLine._protocol.get(i));
            if(res == false){
               System.out.println("PoliMapMatchProtLine "+prefix);
               finalRes = res;
            }
         }
      }
      
      return finalRes;
   }
   
   @Override
   public String getIFString(int indentLevel) {
	   String retString = Util.getIndentString(indentLevel) + "Protocol";
	   
	   for (Protocol proto : _protocol) {
		   retString += " " + proto;
	   }
	   
	   return retString;
   }
}