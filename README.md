[![Clojars Project](https://img.shields.io/clojars/v/cljspad/depstrap.svg)](https://clojars.org/cljspad/depstrap)

# depstrap

depstrap is a repository of open-source ClojureScript libraries that have been packaged for use in self-hosted environments.

You can use depstrap with the [cljs.js](http://cljs.github.io/api/cljs.js/) bootstrapped compiler and [shadow-cljs](http://shadow-cljs.org/).

Visit https://deps.cljspad.dev to browse all available libraries.

This is how [cljspad](https://cljspad.dev) resolves its dependencies at runtime.

## Usage

Add the following dependency to your project:

```clojure
[cljspad/depstrap "0.1.0"]
```

**Note:** depstrap requires [shadow-cljs](http://shadow-cljs.org/). This is because it wraps the `shadow.cljs.bootstrap.browser` API, and repository libraries have been compiled with `{:target :bootstrap}`. You can read more [here](https://code.thheller.com/blog/shadow-cljs/2017/10/14/bootstrap-support.html)

And then:

```clojure
(require '[cljs.js :as cljs.js])
(require '[depstrap.api :as depstrap])

(defn print-result [result]
  (js/console.log result))

(defonce compiler-state (cljs.js/compiler-state))

(defn eval-opts
  [compiler-state]
  {:eval cljs.js/js-eval
   :load (partial depstrap/load compiler-state)})

(defn eval-ratom []
  (cljs.js/eval-str
    compiler-state
    "(reagent.core/atom 1)"
    "[test]"
    (eval-opts compiler-state)
    print-result))

(def opts
  {:depstrap/dependencies '[[reagent "1.0.0-alpha2"]]
   :load-on-init          #{'reagent.core}})

(depstrap/init compiler-state opts eval-ratom)
```

### Configuration options

The second argument to `depstrap.api/init` is an options map:

* `:load-on-init`: a set of namespaces to load on initialization
* `:depstrap/dependencies`: a collection of dependencies to load
* `:depstrap/repository`: the repository URL that resolves the dependencies (default: `https://deps.cljspad.dev`)

## Contributing 

If you would like to submit a library to the depstrap repository, please create an EDN definition in `repository/` like:

```clojure
{:package [reagent "1.0.0-alpha2"]
 :entries [reagent.dom reagent.core]}
```
