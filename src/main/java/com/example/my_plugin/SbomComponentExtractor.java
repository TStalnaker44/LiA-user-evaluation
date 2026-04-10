package com.example.my_plugin;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

public final class SbomComponentExtractor {
    private SbomComponentExtractor() {
    }

    public static List<Element> extractDependencyComponents(Document document) {
        List<Element> components = new ArrayList<>();
        if (document == null) {
            return components;
        }

        Element root = document.getDocumentElement();
        if (root == null) {
            return components;
        }

        NodeList rootChildren = root.getChildNodes();
        for (int i = 0; i < rootChildren.getLength(); i++) {
            Node node = rootChildren.item(i);
            if (!(node instanceof Element child) || !"components".equals(child.getTagName())) {
                continue;
            }

            NodeList componentNodes = child.getChildNodes();
            for (int j = 0; j < componentNodes.getLength(); j++) {
                Node componentNode = componentNodes.item(j);
                if (componentNode instanceof Element component && "component".equals(component.getTagName())) {
                    components.add(component);
                }
            }
        }

        return components;
    }
}
