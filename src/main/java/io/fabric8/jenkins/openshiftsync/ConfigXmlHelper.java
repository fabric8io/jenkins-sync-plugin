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
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.Objects;

/**
 */
public class ConfigXmlHelper {
  protected static final String GITHUB_SCM_NAVIGATOR_ELEMENT = "org.jenkinsci.plugins.github__branch__source.GitHubSCMNavigator";
  protected static final String REGEX_SCM_SOURCE_FILTER_TRAIT_ELEMENT = "jenkins.scm.impl.trait.RegexSCMSourceFilterTrait";


  /**
   * Removes the given repository name from the config.xml Document for organisation jobs
   *
   * @param doc             the config.xml parsed as XML
   * @param buildConfigName the name of the github repo
   * @return true if the XML was modified
   */
  public static boolean removeOrganisationPattern(Document doc, String buildConfigName) {
    boolean answer = false;
    Element githubNavigator = getGithubScmNavigatorElement(doc);
    if (githubNavigator == null) {
      return false;
    }
    if (removePattern(XmlUtils.firstChild(githubNavigator, "pattern"), buildConfigName)) {
      answer = true;
    }
    Element traitsElement = XmlUtils.firstChild(githubNavigator, "traits");
    if (traitsElement != null) {
      Element regexScmSourceFilterElement = XmlUtils.firstChild(traitsElement, REGEX_SCM_SOURCE_FILTER_TRAIT_ELEMENT);
      if (regexScmSourceFilterElement != null) {
        if (removePattern(XmlUtils.firstChild(regexScmSourceFilterElement, "regex"), buildConfigName)) {
          answer = true;
        }
      }
    }
    return answer;
  }

  private static boolean removePattern(Element patternElement, String buildConfigName) {
    if (patternElement != null) {
      String oldPattern = patternElement.getTextContent();
      String newPattern = JenkinsUtils.removePattern(oldPattern, buildConfigName);
      if (newPattern != null && !Objects.equals(oldPattern, newPattern)) {
        XmlUtils.setElementText(patternElement, newPattern);
        return true;
      }
    }
    return false;
  }

  protected static Element getGithubScmNavigatorElement(Document doc) {
    Element githubNavigator = null;
    Element rootElement = doc.getDocumentElement();
    if (rootElement != null) {
      NodeList githubNavigators = rootElement.getElementsByTagName(GITHUB_SCM_NAVIGATOR_ELEMENT);
      for (int i = 0, size = githubNavigators.getLength(); i < size; i++) {
        Node item = githubNavigators.item(i);
        if (item instanceof Element) {
          Element element = (Element) item;
          githubNavigator = element;
          break;
        }
      }
    }
    return githubNavigator;
  }

}
