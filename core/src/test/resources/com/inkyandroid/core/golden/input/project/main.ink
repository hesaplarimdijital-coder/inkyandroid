INCLUDE second.ink
# title: Project for findSymbol
Start of the story. -> intro

=== intro ===
= opening
* (pick) Pick it up -> intro.closing
* (leave) Leave it
- (after) After the choice. -> after_label
-> second_knot

= closing
- (end_gather) The end. -> opening.pick
-> shared

=== shared ===
In main. -> shared_stitch
= shared_stitch
-> pick
-> intro.opening.leave
-> intro.nonexistent.deeper
-> undefined

=== function helper(x)
~ return x
