[↑ Curriculum index](README.md) · [← prev](37-a-multi-index-RAG-pipeline.md) · [next →](39-image-support.md)

---

#### Summary

**Important Note: Extended Thinking is not compatible with some other features, notable message pre-filling and temperature. See the full list of restrictions here: **[**https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking#feature-compatibility**](https://docs.anthropic.com/en/docs/build-with-claude/extended-thinking#feature-compatibility)

Extended thinking is Claude's advanced reasoning feature that gives the model time to work through complex problems before generating a final response. Think of it as Claude's "scratch paper" - you can see the reasoning process that leads to the answer, which helps with transparency and often results in better quality responses.

## How Extended Thinking Works

When extended thinking is enabled, Claude's response changes from a simple text block to a structured response containing two parts:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542429%2F08_-_001_-_Extended_Thinking_04.1748542429342.jpg)

With thinking enabled, you get both the reasoning process and the final answer:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542429%2F08_-_001_-_Extended_Thinking_05.1748542429732.jpg)

The key benefits include:

- Better reasoning capabilities for complex tasks
- Increased accuracy on difficult problems
- Transparency into Claude's thought process

However, there are important trade-offs:

- Higher costs (you pay for thinking tokens)
- Increased latency (thinking takes time)
- More complex response handling in your code

## When to Use Extended Thinking

The decision is straightforward: use your prompt evaluations. Run your prompts without thinking first, and if the accuracy isn't meeting your requirements after you've already optimized your prompt, then consider enabling extended thinking. It's a tool for when standard prompting isn't quite getting you there.

## Response Structure and Security

Extended thinking responses include a special signature system for security:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542430%2F08_-_001_-_Extended_Thinking_06.1748542430208.jpg)

The signature is a cryptographic token that ensures you haven't modified the thinking text. This prevents developers from tampering with Claude's reasoning process, which could potentially lead the model in unsafe directions.

## Redacted Thinking

Sometimes you'll receive a redacted thinking block instead of readable reasoning text:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542430%2F08_-_001_-_Extended_Thinking_08.1748542430775.jpg)

This happens when Claude's thinking process gets flagged by internal safety systems. The redacted content contains the actual thinking in encrypted form, allowing you to pass the complete message back to Claude in future conversations without losing context.

## Implementation

To enable extended thinking in your code, you need to add two parameters to your chat function:

```
def chat(
    messages,
    system=None,
    temperature=1.0,
    stop_sequences=[],
    tools=None,
    thinking=False,
    thinking_budget=1024
):
```

The thinking budget sets the maximum tokens Claude can use for reasoning. The minimum value is 1024 tokens, and your `max_tokens` parameter must be greater than your thinking budget.

Add the thinking configuration to your API parameters:

```
if thinking:
    params["thinking"] = {
        "type": "enabled",
        "budget": thinking_budget
    }
```

Then call it with thinking enabled:

```
chat(messages, thinking=True)
```

## Testing Redacted Responses

For testing purposes, you can force Claude to return a redacted thinking block by sending a special trigger string. This helps ensure your application handles redacted responses gracefully without crashing.

Extended thinking is a powerful feature when you need Claude to tackle complex reasoning tasks, but use it judiciously given the cost and latency implications. Start with standard prompting, optimize thoroughly, then add thinking when you need that extra reasoning capability.

#### Downloads

- [001_thinking_complete.ipynb](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762980359/001_thinking_complete.ipynb?response-content-disposition=attachment&Expires=1776659025&Signature=tkSl3QbHlaOO0Vnpt42EtKcUf~WsBThVpDO4Ohm-B0VvxqaMHWnsVVfNskNgVnp31oM552XkzzL3WNt0fE0C3rZyQevBXLkLPUCVnt65O51CmUpt8PXSl6MZU-1aKnv47Diqz6F5TT4Sv21SS3Bh1TpjjC7gelpb0xdu4aM5cBTjmujoiuvqMTDMC4PYNkePobjGC86I~9S0onXjcI6JFFjzf86v0smQr5eDCUv9DnstxNzgDa8pIXKN8DXE1JktzfJF4BX65adH-80fogiOZwOZm1id0JSKzU5LwCaud7luGjkVjcM4Og30Lj3OvcG56ZeJSpdvjz6zmqyjzS1iKA__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
- [001_thinking.ipynb](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762980359/001_thinking.ipynb?response-content-disposition=attachment&Expires=1776659025&Signature=uTG3yQhwKDO0O6YFPlaAfeFVx3TQWGxLXJyez72CkEtIhIPiyWdQuUaeIQPcXSyc0sCKshqmVR39A-LsSz7gf2OhZtWHMmKYT2WTMTCT0xH3-C9XKmGZIeyDWLwY8aWp0N3TG1KMe4vMOPqDomVvlk1qJvhMOR3MKp90pJ27FQ3Pygg5j6WRPhgFgzJeFlUulHXrRDGRxpkbU7oWKJEYlGmRP~W~7rA0FqooxCU9t4~Lcw-dk2C1W7g6Vlk5mYrbUeR4CC07CkDHaa8pr-J2i-TFWhnQQb4c-UWEhR7ohcp83~oPi3XdQaZtrLtzo1iRKe0EECqrJoEyGJEs0fMhlQ__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
