VAR turns = 0
-> loop
== loop ==
~ turns++
Turn {turns}.
Stopping: {one|two|three}.
Cycle: {&red|green|blue}.
Once: {!first|second}.
Count of this knot: {loop}.
{ turns > 1: Turns since middle: {TURNS_SINCE(-> middle)}. }
{ stopping:
    - A long first visit.
    - A shorter second visit.
    - The same from now on.
}
{ cycle:
    - Tick.
    - Tock.
}
{ once:
    - Only this once.
}
-> middle
= middle
{middle > 2: Seen the middle {middle} times.}
{ turns:
- 1: the first turn
- 2: the second turn
- else: a later turn
}
Choices so far: {CHOICE_COUNT()}.
* [A once-only choice]
  Taken once.
  -> loop
+ [A sticky choice]
  Sticky taken, {loop.middle} visits.
  -> loop
* {turns >= 2} [A choice for later turns]
  Later choice taken.
  -> loop
+ {turns >= 5} [Finish]
  -> END
* -> fallback
= fallback
The fallback ran.
-> END
