package com.example.my_plugin;

import com.google.gson.JsonObject;

public class License {
    public String type;
    public String url;

    public License(String type)
    {
        this.type = type;
        this.url = null;
    }

    public License(String type, String url)
    {
        this.type = type;
        this.url = url;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", this.type);
        obj.addProperty("url", this.url);
        return obj;
    }

    public String getType()
    {
        return this.type;
    }

    public String getUrl()
    {
        return this.url;
    }

    @Override
    public String toString() { return this.type; }

    //By default, don't worry about the URL when comparing licenses
    @Override
    public boolean equals(Object obj) { return this.equals(obj, false); }

    public boolean equals(Object obj, boolean checkURL)
    {
        if (!(obj instanceof License)) { return false; } //check that the other object is a license
        License license = (License)obj;

        boolean out = this.getType().equals(license.getType()); //check if the license type is the same
        if (checkURL) { out = out && this.getUrl().equals(license.getUrl()); } //if checkURL is true, also check if the URL is the same
        return out;
    }

    @Override
    public int hashCode() { return this.type.hashCode(); }
}