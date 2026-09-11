
import "./custom-formatters.js"
import { importObject, setWasmExports } from './composeApp.import-object.mjs'

let wasmInstance;

const wasmOptions = { builtins: ['js-string'], importedStringConstants: "'" }

try {
  if ((typeof process !== 'undefined') && (process.release.name === 'node')) {
    const fs = await import(/* webpackIgnore: true */'node:fs');
    const url = await import(/* webpackIgnore: true */'node:url');
    const filepath = import.meta.resolve('./composeApp.wasm');
    const wasmBuffer = fs.readFileSync(url.fileURLToPath(filepath));
    const wasmModule = new WebAssembly.Module(wasmBuffer, wasmOptions);
    wasmInstance = new WebAssembly.Instance(wasmModule, importObject);
  } else if (typeof Deno !== 'undefined') {
    const path = await import(/* webpackIgnore: true */'https://deno.land/std/path/mod.ts');
    const binary = Deno.readFileSync(path.fromFileUrl(import.meta.resolve('./composeApp.wasm')));
    const module = await WebAssembly.compile(binary, wasmOptions);
    wasmInstance = await WebAssembly.instantiate(module, importObject);
  } else if (
    (typeof d8 !== 'undefined' // V8
      || typeof inIon !== 'undefined' // SpiderMonkey
      || typeof jscOptions !== 'undefined' // JavaScriptCore
    )
  ) {
    const filepath = import.meta.url.replace(/\.mjs$/, '.wasm');
    const wasmBuffer = read(filepath, 'binary');
    const wasmModule = new WebAssembly.Module(wasmBuffer, wasmOptions);
    wasmInstance = new WebAssembly.Instance(wasmModule, importObject);
  } else {
    wasmInstance = (await WebAssembly.instantiateStreaming(fetch(new URL('./composeApp.wasm',import.meta.url).href), importObject, wasmOptions)).instance;
  }
} catch (e) {
  if (e instanceof WebAssembly.CompileError) {
    let text = `Please make sure that your runtime environment supports the latest version of Wasm GC and Exception-Handling proposals.
For more information, see https://kotl.in/wasm-help
`;
    if (typeof console !== "undefined" && console.error !== void 0) {
      console.error(text);
    } else {
      const t = "\n" + text;
      if (typeof console !== "undefined" && console.log !== void 0)
        console.log(t);
      else
        print(t);
    }
  }
  throw e;
}

const exports = wasmInstance.exports

let memoryFirstTimeAccess = true;
const memoryProxy = new Proxy(importObject.intrinsics.memory, {
    get(target, prop, receiver) {
        if (memoryFirstTimeAccess) {
            memoryFirstTimeAccess = false;
            console.error('Accessing `memory` via `wasmExports` is deprecated. Use `kotlin.wasm.unsafe.wasmMemory` or update dependencies. Read more: https://kotl.in/vr3szr');
        }
        return Reflect.get(target, prop);
    }
});
const wasmExports = new Proxy(memoryProxy, {
    get(target, prop, receiver) {
        if (prop == 'memory') {
            return target;
        } else {
            throw new Error('Accessing exports via `wasmExports` is no longer supported. Remove usages or update dependencies. Read more: https://kotl.in/vr3szr');
        }
    }
});

const wasmMemory = wasmExports.memory;
export { wasmMemory as memory }

export const {
    applyOverrides,
    _start
} = exports

setWasmExports(wasmExports);

exports._start();
