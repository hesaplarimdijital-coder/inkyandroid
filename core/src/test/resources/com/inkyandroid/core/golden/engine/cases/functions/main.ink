EXTERNAL shout(text)
EXTERNAL add(a, b)
VAR total = 0
VAR name = "world"
{greet(name)}
Two times four is {multiply(2, 4)}.
Factorial of 5 is {factorial(5)}.
~ bump(total, 3)
~ bump(total, 4)
Total is {total}.
{shout("fallback")}
One and two make {add(1, 2)}.
{describe(0)} {describe(1)} {describe(5)}
~ temp result = both()
Both gave {result}.
{is_even(4): four is even} and {not is_even(3): three is not}.
* [Call a function from a choice {multiply(3, 3)}]
  Chosen: {greet("chooser")}
- -> END
== function greet(who) ==
~ return "Hello, {who}!"
== function multiply(a, b) ==
~ return a * b
== function factorial(n) ==
{ n <= 1:
    ~ return 1
}
~ return n * factorial(n - 1)
== function bump(ref value, by) ==
~ value = value + by
== function shout(text) ==
The ink version shouts: {text}
== function add(a, b) ==
~ return a + b
== function describe(n) ==
{ n:
- 0: none
- 1: one
- else: many
}
== function both() ==
printed by the function
~ return "returned"
== function is_even(n) ==
~ return n % 2 == 0
