[![Clojars Project](https://img.shields.io/clojars/v/cljspad/webjars-api.svg)](https://clojars.org/cljspad/webjars-api)

# webjars

webjars is a ClojureScript repository (think [Clojars](https://clojars.org/)) for self-hosted ClojureScript projects. 

You can use webjars with the [cljs.js](http://cljs.github.io/api/cljs.js/) bootstrapped compiler and [shadow-cljs](http://shadow-cljs.org/)

## What/How?

The webjars API wraps `shadow.cljs.bootstrap.browser` under the hoods to resolve dependencies. You can read about this API [here](https://code.thheller.com/blog/shadow-cljs/2017/10/14/bootstrap-support.html)

webjars are simply pre-compiled `{:target :bootstrap}` libraries uploaded to S3 for effortless consumption :)

Visit https://webjars.cljspad.dev to browse all available libraries.  

## Usage

Add the following dependency to your project:

```clojure 
[cljspad/webjars-api "0.1.0"]
```

And then:

```clojure
(require '[cljs.js :as cljs.js])
(require '[webjars.api :as webjars])

(defn print-result [result]
  (js/console.log result))

(defonce compiler-state (cljs.js/compiler-state))

(defn eval-opts
  [compiler-state]
  {:eval cljs.js/js-eval
   :load (partial webjars/load compiler-state)})

(defn eval-ratom []
  (cljs.js/eval-str 
    compiler-state 
    "(rg/atom 1)" 
    "[test]"
    (eval-opts compiler-state)
    print-result))

(def opts
  {:webjars/dependencies '[[reagent "1.0.0-alpha2"]] 
   :load-on-init         #{'reagent.core}})

(webjars/init compiler-state opts eval-ratom)
```

## Contributing 

If you would like to submit a library to the webjars repository, please create an EDN definition in `repository/` like:

```clojure
{:package [reagent "1.0.0-alpha2"]
 :entries [reagent.dom reagent.core]}
```
