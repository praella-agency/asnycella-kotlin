# asnycella-kotlin
A library to write easier and more manageable asynchronous code for multi-platform usage.

```java
async {
  someTask()
}
```
Based on Swift's approach of the asynchronous imperative code, this was implemented into Kotlin for usage in multithread concurrencies. Calling await in
the background thread does not freeze the main thread.
This library is multiplatform compatible under the KMM module | gradle implementation and exports clean and concise swift code for the iOS application.

## Usage

```kotlin
async {
  val multiThreading = await {
    LongAwaitedFunc()
  }
}
```
Await can also be run in for loops, try/catches and almost anything allowing concurrency.
Errors in code, while dispatching async/await can be noticed with the ```onError``` modifier

```kotlin
async {
   val dynamicText = await {
      // Exception is thrown in the background thread
   }
   // Process dynamic text ehre
}.onError {
   // Handle exception in main thread
}
```
