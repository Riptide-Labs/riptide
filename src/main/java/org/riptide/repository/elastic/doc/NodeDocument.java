package org.riptide.repository.elastic.doc;

import java.util.LinkedList;
import java.util.List;

import com.google.gson.annotations.SerializedName;
import org.riptide.flows.Flow;

public class NodeDocument {
    @SerializedName("foreign_source")
    private String foreignSource;

    @SerializedName("foreign_id")
    private String foreignId;

    @SerializedName("node_id")
    private Integer nodeId;

    @SerializedName("interface_id")
    private Integer interfaceId;

    @SerializedName("categories")
    private List<String> categories = new LinkedList<>();

    public void setForeignSource(String foreignSource) {
        this.foreignSource = foreignSource;
    }

    public String getForeignSource() {
        return foreignSource;
    }

    public void setForeignId(String foreignId) {
        this.foreignId = foreignId;
    }

    public String getForeignId() {
        return foreignId;
    }

    public Integer getNodeId() {
        return nodeId;
    }

    public void setNodeId(Integer nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getInterfaceId() {
        return this.interfaceId;
    }

    public void setInterfaceId(final Integer interfaceId) {
        this.interfaceId = interfaceId;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }

    public static NodeDocument from(final Flow.NodeInfo info) {
        if (info == null) {
            return null;
        }

        final NodeDocument doc = new NodeDocument();
        doc.setForeignSource(info.getForeignSource());
        doc.setForeignId(info.getForeignId());
        doc.setNodeId(info.getNodeId());
        doc.setInterfaceId(info.getInterfaceId());
        doc.setCategories(info.getCategories());
        return doc;
    }
}
