"What's that?" my master asked.
* "I am somewhat tired[."]," I repeated.
  "Really," he responded. "How deleterious."
* "Nothing, Monsieur!"[] I replied.
  "Very good, then."
* (said_true) "I said, this journey is appalling[."] and I want no more of it."
  "Ah," he replied, not unkindly. "I see you are feeling frustrated."
  * * [Agree]
      "I am."
      * * * [Loudly] "LOUDLY."
      * * * [Quietly] "quietly."
      - - - (after_inner) He nodded.
  * * [Deny] "I am not."
  - - He smiled.
- (rejoin) With that Monsieur Fogg left the room. {said_true: He had heard the truth.}
{after_inner: He had nodded, once.}
* [Follow him] -> corridor
* {said_true} [Sulk] -> sulk
* -> 
  Nothing else to do.
  -> END
== corridor ==
The corridor.
- (opts)
* [Look left] Nothing left.
  -> opts
* [Look right] Nothing right.
  -> opts
* {opts > 1} [Give up] -> END
+ [Wait] {opts} waits.
  -> opts
== sulk ==
I sulked.
-> END
