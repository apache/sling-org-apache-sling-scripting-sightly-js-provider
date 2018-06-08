[<img src="http://sling.apache.org/res/logos/sling.png"/>](http://sling.apache.org)

 [![Build Status](https://builds.apache.org/buildStatus/icon?job=sling-org-apache-sling-scripting-sightly-js-provider-1.8)](https://builds.apache.org/view/S-Z/view/Sling/job/sling-org-apache-sling-scripting-sightly-js-provider-1.8) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.apache.sling/org.apache.sling.scripting.sightly.js.provider/badge.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.apache.sling%22%20a%3A%22org.apache.sling.scripting.sightly.js.provider%22) [![JavaDocs](https://www.javadoc.io/badge/org.apache.sling/org.apache.sling.scripting.sightly.js.provider.svg)](https://www.javadoc.io/doc/org.apache.sling/org.apache.sling.scripting.sightly.js.provider) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0) [![scripting](https://sling.apache.org/badges/group-scripting.svg)](https://github.com/apache/sling-aggregator/blob/master/docs/groups/scripting.md)

# Apache Sling Scripting HTL JavaScript Use Provider

This module is part of the [Apache Sling](https://sling.apache.org) project.

This bundle allows HTL's Use API to access JS scripts. It also wraps Sling's JS engine in a simulated event loop.

The bundle also contains a bindings values provider that adds an API layer accessible from HTL & JS. The implementation of that API can be found in `src/main/resources/SLING-INF`.
