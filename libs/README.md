These are compile-only inputs. Run `python3 tools/fetch_compile_dependencies.py` from
the project root to fetch and verify the exact dependencies documented in
`DEPENDENCIES.json`. Sable supplies its embedded Companion Common library at runtime;
the extracted Companion JAR is only needed on the compiler classpath.
