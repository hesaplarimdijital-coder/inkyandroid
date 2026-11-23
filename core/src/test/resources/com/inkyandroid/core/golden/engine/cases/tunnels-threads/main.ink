VAR depth = 0
Start.
-> tunnel_one ->
Back from the first tunnel.
-> tunnel_one -> tunnel_two ->
Back from both.
-> nested ->
Back from nested ({depth}).
-> hub
== tunnel_one ==
In tunnel one.
->->
== tunnel_two ==
In tunnel two.
* [Stay a while]
  Stayed.
* [Leave]
- ->->
== nested ==
~ depth++
Going down, {depth}.
{ depth < 3: -> nested -> }
Coming up.
->->
== hub ==
At the hub.
<- thread_a
<- thread_b(7)
* [Hub's own choice] -> ending
== thread_a ==
Thread A speaks.
* [From thread A] A was chosen. -> ending
== thread_b(n) ==
Thread B speaks of {n}.
* [From thread B] B was chosen with {n}. -> ending
== ending ==
-> tunnel_out -> DONE
== tunnel_out ==
Leaving through a tunnel elsewhere.
->-> finale
== finale ==
The finale.
-> END
