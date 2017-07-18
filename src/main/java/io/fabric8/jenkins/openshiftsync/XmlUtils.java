/**
 * Copyright (C) 2016 Red Hat, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

/**
 */
public class XmlUtils {
  private static Transformer transformer;
  private static TransformerFactory transformerFactory;

  public static Document parseDoc(String xml)
    throws ParserConfigurationException,
    SAXException,
    IOException {
    try (StringReader reader = new StringReader(xml)) {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      InputSource source = new InputSource(reader);
      return builder.parse(source);
    }
  }

  public static String toXml(Document document) throws TransformerException {
    Transformer transformer = getTransformer();
    StringWriter buffer = new StringWriter();
    transformer.transform(new DOMSource(document), new StreamResult(buffer));
    return buffer.toString();
  }

  /**
   * If the node is attached to a parent then detach it
   */
  public static void detach(Node node) {
    if (node != null) {
      Node parentNode = node.getParentNode();
      if (parentNode != null) {
        parentNode.removeChild(node);
      }
    }
  }

  protected static Transformer getTransformer() throws TransformerConfigurationException {
    if (transformer == null) {
      transformer = getTransformerFactory().newTransformer();
    }
    return transformer;
  }

  protected static TransformerFactory getTransformerFactory() {
    if (transformerFactory == null) {
      transformerFactory = TransformerFactory.newInstance();
    }
    return transformerFactory;
  }
}
