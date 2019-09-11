package org.batfish.representation.cisco.eos;

import java.io.Serializable;
import javax.annotation.Nullable;

/** Base class for all Arista BGP neighbors */
public abstract class AristaBgpNeighbor implements Serializable {
  @Nullable private Integer _allowAsIn;
  @Nullable private Boolean _autoLocalAddr;
  @Nullable private String _description;
  @Nullable private Boolean _dontCapabilityNegotiate;
  @Nullable private Integer _ebgpMultihop;
  @Nullable private Boolean _enforceFirstAs;
  @Nullable private Long _localAs;
  @Nullable private Boolean _nextHopSelf;
  @Nullable private Long _remoteAs;

  @Nullable
  public Integer getAllowAsIn() {
    return _allowAsIn;
  }

  public void setAllowAsIn(@Nullable Integer allowAsIn) {
    _allowAsIn = allowAsIn;
  }

  @Nullable
  public Boolean getAutoLocalAddr() {
    return _autoLocalAddr;
  }

  public void setAutoLocalAddr(@Nullable Boolean autoLocalAddr) {
    _autoLocalAddr = autoLocalAddr;
  }

  @Nullable
  public String getDescription() {
    return _description;
  }

  public void setDescription(@Nullable String description) {
    _description = description;
  }

  @Nullable
  public Boolean getDontCapabilityNegotiate() {
    return _dontCapabilityNegotiate;
  }

  public void setDontCapabilityNegotiate(@Nullable Boolean dontCapabilityNegotiate) {
    _dontCapabilityNegotiate = dontCapabilityNegotiate;
  }

  @Nullable
  public Integer getEbgpMultihop() {
    return _ebgpMultihop;
  }

  public void setEbgpMultihop(@Nullable Integer ebgpMultihop) {
    _ebgpMultihop = ebgpMultihop;
  }

  @Nullable
  public Boolean getEnforceFirstAs() {
    return _enforceFirstAs;
  }

  public void setEnforceFirstAs(@Nullable Boolean enforceFirstAs) {
    _enforceFirstAs = enforceFirstAs;
  }

  @Nullable
  public Long getLocalAs() {
    return _localAs;
  }

  public void setLocalAs(@Nullable Long localAs) {
    _localAs = localAs;
  }

  @Nullable
  public Boolean getNextHopSelf() {
    return _nextHopSelf;
  }

  public void setNextHopSelf(@Nullable Boolean nextHopSelf) {
    _nextHopSelf = nextHopSelf;
  }

  @Nullable
  public Long getRemoteAs() {
    return _remoteAs;
  }

  public void setRemoteAs(@Nullable Long remoteAs) {
    _remoteAs = remoteAs;
  }
}