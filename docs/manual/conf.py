# Copyright(c) 2026 Sven Trittler
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v. 2.0 which is available at
# http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
# v. 1.0 which is available at
# http://www.eclipse.org/org/documents/edl-v10.php.
#
# SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

from pathlib import Path
from datetime import datetime
import json

# Match the Cyclone C++/Python manual metadata and substitution convention.
variables = json.loads((Path(__file__).parent / "variables.json").read_text())
variables["copyright"] = variables["copyright"].replace(":year:", str(datetime.now().year))
project = variables["project"]
copyright = variables["copyright"]
author = variables["author"]
version = variables["version"]
release = variables["release"]

extensions = [
    "sphinx.ext.intersphinx",
    "sphinx.ext.viewcode",
    "sphinx.ext.autosummary",
    "sphinx.ext.extlinks",
    "sphinx.ext.todo",
    "sphinx_tabs.tabs",
]
templates_path = ["_templates"]
language = "en"
exclude_patterns = ["_build", "Thumbs.db", ".DS_Store", "**/*.part.rst"]
needs_sphinx = "4.0"
try:
    import piccolo_theme
    extensions.append("piccolo_theme")
    html_theme = "piccolo_theme"
    html_css_files = ["css/helpers.css"]
    html_js_files = ["js/helpers.js"]
except ImportError:
    import warnings
    warnings.warn("piccolo_theme is not installed. Falling back to alabaster.")
    html_theme = "alabaster"
html_static_path = ["_static"]
html_title = f"{project}, {version}"
html_short_title = html_title
highlight_language = "java"
autosummary_generate = True
rst_epilog = "\n".join(f".. |var-{key}| replace:: {value}" for key, value in variables.items())
