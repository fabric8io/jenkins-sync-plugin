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
package io.fabric8.jenkins.openshiftsync.systest.support;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.junit.Assert.assertTrue;

/**
 */
public class DomAssertions {
  public static Element assertHasElementWithName(Document doc, String tagName, String description) {
    NodeList nodeList = doc.getElementsByTagName(tagName);
    assertTrue("No element <" + tagName + "> in " + description, nodeList.getLength() > 0);
    Node node = nodeList.item(0);
    assertTrue("DOM node for   <" + tagName + "> should be an Element in " + description, node instanceof Element);
    return (Element) node;
  }
}
