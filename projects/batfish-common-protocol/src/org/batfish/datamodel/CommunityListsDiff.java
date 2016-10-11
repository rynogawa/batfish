package org.batfish.datamodel;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CommunityListsDiff extends ConfigDiffElement {

   private static final String DIFF = "diff";
   private Map<String, Map<String, CommunityList>> _diff;

   @JsonCreator()
   public CommunityListsDiff() {

   }

   public CommunityListsDiff(NavigableMap<String, CommunityList> a,
         NavigableMap<String, CommunityList> b) {
      super(a.keySet(), b.keySet());
      _diff = new HashMap<>();
      for (String name : _common) {
         if (a.get(name).equals(b.get(name))) {
            _identical.add(name);
         }
         else {
            Map<String, CommunityList> info = new HashMap<>();
            info.put("a", a.get(name));
            info.put("b", b.get(name));
            _diff.put(name, info);
         }
      }
   }

   @JsonProperty(DIFF)
   public Map<String, Map<String, CommunityList>> getDiff() {
      return _diff;
   }

   public void setDiff(Map<String, Map<String, CommunityList>> d) {
      this._diff = d;
   }
}
