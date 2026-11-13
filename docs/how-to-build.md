<!--
Copyright(c) 2026 Sven Trittler

This program and the accompanying materials are made available under the
terms of the Eclipse Public License v. 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
v. 1.0 which is available at
http://www.eclipse.org/org/documents/edl-v10.php.

SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
-->

# How to build the documentation

The layout follows the Cyclone DDS C++ and Python wrappers: `docs/manual` contains
reStructuredText pages, `conf.py`, `variables.json` and the Piccolo theme assets.
The manual entry point is `docs/manual/index.rst`.

From the repository root:

```sh
python3 -m venv .venv-docs
. .venv-docs/bin/activate
python -m pip install -r docs/requirements.txt
sphinx-build -W --keep-going docs/manual build/docs/html
python -m http.server --directory build/docs/html
```

The build needs no Java/native runtime and can run in an automated documentation
pipeline. Project metadata and `|var-*|` substitutions follow the same JSON
convention as the C++ and Python manuals. Java code highlighting replaces their
language-specific API tooling. This manual currently provides an API overview;
it does not claim to generate complete Javadoc reference pages.
