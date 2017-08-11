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

import io.fabric8.utils.DomHelper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static io.fabric8.jenkins.openshiftsync.systest.support.DomAssertions.assertHasElementWithName;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 */
public class JenkinsConfigXmlAssertions {

  protected static final String GITHUB_SCM_NAVIGATOR_ELEMENT = "org.jenkinsci.plugins.github__branch__source.GitHubSCMNavigator";
  protected static final String REGEX_SCM_SOURCE_FILTER_TRAIT_ELEMENT = "jenkins.scm.impl.trait.RegexSCMSourceFilterTrait";


  /**
   * Returns the pattern from the given Document
   */
  public static String assertHasGithubNavigatorPattern(Document doc, String description) {
    Element pattern = assertHasGithubNavigatorPatternElement(doc, description);
    return pattern.getTextContent();
  }

  public static Element assertHasGithubNavigatorPatternElement(Document doc, String description) {
    Element githubNavigator = assertHasElementWithName(doc, GITHUB_SCM_NAVIGATOR_ELEMENT, description);

    Element pattern = DomHelper.firstChild(githubNavigator, "pattern");
    if (pattern == null) {
      Element traitsElement = DomHelper.firstChild(githubNavigator, "traits");
      if (traitsElement != null) {
        Element regexScmSourceFilterElement = DomHelper.firstChild(traitsElement, REGEX_SCM_SOURCE_FILTER_TRAIT_ELEMENT);
        if (regexScmSourceFilterElement != null) {
          pattern = DomHelper.firstChild(regexScmSourceFilterElement, "regex");
        }
      }
    }
    assertTrue("Jenkins Config XML <" + GITHUB_SCM_NAVIGATOR_ELEMENT
        + "> element does not contain <pattern> or <traits><" + REGEX_SCM_SOURCE_FILTER_TRAIT_ELEMENT
        + "><regex> for " + description, pattern != null);
    return pattern;
  }

  public static Set<String> patternSet(String pattern) {
    assertNotNull("pattern should not be null", pattern);
    if (pattern.isEmpty()) {
      return new HashSet<>();
    }
    String[] array = pattern.split("\\|");
    return new TreeSet<>(Arrays.asList(array));
  }

  public static void assertRemovePattern(Document doc, String bcName, String description) {
    Element patternElement = assertHasGithubNavigatorPatternElement(doc, description);

    String oldPattern = patternElement.getTextContent();
    String newPattern = removePattern(oldPattern, bcName);

    assertTrue("Could not find " + bcName + " in pattern " + oldPattern + " for " + description, newPattern != null);
    assertTrue("Did not manage to remove " + bcName + " from pattern " + oldPattern + " for " + description, !oldPattern.equals(newPattern));
    patternElement.setTextContent(newPattern);
  }


  /**
   * Code copied from JenkinsUtils
   */
  private static String removePattern(String pattern, String name) {
    if (pattern.equals(name)) {
      pattern = "";
    } else {
      String prefix = name + "|";
      String suffix = "|" + name;
      String middle = "|" + name + "|";
      if (pattern.startsWith(prefix)) {
        pattern = pattern.substring(prefix.length());
      } else if (pattern.endsWith(suffix)) {
        pattern = pattern.substring(0, pattern.length() - suffix.length());
      } else {
        int idx = pattern.indexOf(middle);
        if (idx < 0) {
          return null;
        }
        String separator = idx > 0 ? "|" : "";
        pattern = pattern.substring(0, idx) + separator + pattern.substring(idx + middle.length());
      }
    }
    return pattern;
  }
}
