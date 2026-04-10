package com.example.my_plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;



public class Dependency {
    public String group;
    public String name;
    public String version;
    public List<License> licenses;
    public Boolean direct;
    public String introducedBy;
    public List<String> dependencyPath;
    public Integer treeDepth;

    public Dependency(String group, String name, String version, List<License> licenses) {
        this.group = group;
        this.name = name;
        this.version = version;
        this.licenses = licenses;
    }

    public String id() {
        return String.join(":", this.group, this.name, this.version);
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("group", this.group);
        obj.addProperty("name", this.name);
        obj.addProperty("version", this.version);
        if (this.direct != null) {
            obj.addProperty("direct", this.direct);
        }
        if (this.introducedBy != null && !this.introducedBy.isBlank()) {
            obj.addProperty("introducedBy", this.introducedBy);
        }
        if (this.treeDepth != null) {
            obj.addProperty("treeDepth", this.treeDepth);
        }
        if (this.dependencyPath != null && !this.dependencyPath.isEmpty()) {
            JsonArray pathArray = new JsonArray();
            for (String step : this.dependencyPath) {
                pathArray.add(step);
            }
            obj.add("dependencyPath", pathArray);
        }
        JsonArray licensesArray = new JsonArray();
        for (License lic : this.licenses) {
            licensesArray.add(lic.toJson());
        }
        obj.add("licenses", licensesArray);
        return obj;
    }
}


