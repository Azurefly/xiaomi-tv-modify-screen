/*
 * Copyright (C) 2026 小狗便利店投屏 contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package cn.local.livingroomcast.core;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class Xml {
    private Xml() {}
    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
    public static Document parse(String value) throws Exception {
        if (value.contains("<!DOCTYPE") || value.contains("<!ENTITY")) throw new IllegalArgumentException("DTD disabled");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { /* DTDs are rejected before parsing on Android as well. */ }
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
        try { f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { /* Android parser may not expose it. */ }
        var b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        b.setErrorHandler(new DefaultHandler() { @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; } });
        return b.parse(new InputSource(new StringReader(value)));
    }
    public static String local(Node node) { return node.getLocalName() == null ? node.getNodeName() : node.getLocalName(); }
    public static Element soapAction(String body) throws Exception {
        var nodes = parse(body).getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body");
        if (nodes.getLength() != 1) throw new IllegalArgumentException("SOAP Body required");
        Element action = null;
        for (Node n = nodes.item(0).getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) { if (action != null) throw new IllegalArgumentException("One action required"); action = (Element) n; }
        }
        if (action == null) throw new IllegalArgumentException("Action required");
        return action;
    }
    public static Map<String, String> arguments(Element action) {
        Map<String, String> args = new LinkedHashMap<>();
        for (Node n = action.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) args.put(local(n), n.getTextContent());
        return args;
    }
    public static String first(String xml, String tag) {
        try { var nodes = parse(xml).getElementsByTagNameNS("*", tag); return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent(); }
        catch (Exception ignored) { return ""; }
    }
    public static String resourceMime(String metadata) {
        try {
            var nodes = parse(metadata).getElementsByTagNameNS("*", "res");
            if (nodes.getLength() > 0) {
                String[] info = ((Element) nodes.item(0)).getAttribute("protocolInfo").split(":", 4);
                if (info.length == 4 && info[2].contains("/")) return info[2];
            }
        } catch (Exception ignored) { }
        return "";
    }
}
