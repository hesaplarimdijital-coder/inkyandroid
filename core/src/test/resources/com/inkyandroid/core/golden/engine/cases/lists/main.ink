LIST colours = red, (green), blue
LIST sizes = (small = 1), medium = 5, large = 10
LIST inventory = sword, shield, (lamp), rope
VAR bag = (sword, rope)
VAR empty = ()
Colours: {colours}. Sizes: {sizes}. Bag: {bag}. Empty: "{empty}".
~ colours += blue
~ colours -= green
Now colours: {colours}.
Count {LIST_COUNT(colours)}, min {LIST_MIN(colours)}, max {LIST_MAX(colours)}.
All colours: {LIST_ALL(colours)}.
Inverted: {LIST_INVERT(colours)}.
Value of large: {LIST_VALUE(large)}. Value of small: {LIST_VALUE(small)}.
From number: {sizes(5)}.
Range: {LIST_RANGE(LIST_ALL(sizes), 2, 10)}.
{ colours has red: has red | no red } and { colours hasnt green: hasnt green | has green }.
{ colours ? (red, blue): both | not both } and { colours !? green: no green | green }.
{ bag == (sword, rope): bag equal | bag differs }.
{ large > small: large is bigger }.
~ inventory += shield
~ inventory -= lamp
Inventory: {inventory}.
Next after small: {small + 1}. Mixed: {bag + lamp}.
Intersection: {LIST_ALL(inventory) ^ bag}.
{ empty: not empty | is empty }
* {inventory has shield} [Raise the shield]
  ~ inventory -= shield
  Shield gone: {inventory}.
* {inventory has lamp} [Light the lamp]
  Never offered.
- { LIST_COUNT(inventory) } left.
-> END
