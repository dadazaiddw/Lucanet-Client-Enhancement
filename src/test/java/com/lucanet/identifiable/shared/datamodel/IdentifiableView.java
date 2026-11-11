package com.lucanet.identifiable.shared.datamodel;
public class IdentifiableView {
    private final long oid;private final String type;
    public IdentifiableView(long oid,String type){this.oid=oid;this.type=type;}
    public long getObjectID(){return oid;}
    public String getDelegateClass(){return type;}
}
