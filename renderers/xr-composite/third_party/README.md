# Vendored single-header dependencies

These are checked in (small, header-only, permissive) so the build needs only
the Filament SDK fetched at build time.

| file | source | version | license |
|------|--------|---------|---------|
| `json.hpp` | [nlohmann/json](https://github.com/nlohmann/json) | v3.11.3 | MIT |
| `stb_image.h` | [nothings/stb](https://github.com/nothings/stb) | master | MIT / public domain |
| `stb_image_write.h` | [nothings/stb](https://github.com/nothings/stb) | master | MIT / public domain |

Each file carries its own license text inline. `json.hpp` must be included
before any Filament header: `utils/debug.h` defines an `assert_invariant` macro
that otherwise clobbers `nlohmann::json`'s member function of the same name.
