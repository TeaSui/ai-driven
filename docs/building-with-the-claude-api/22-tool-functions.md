[↑ Curriculum index](README.md) · [← prev](21-project-overview.md) · [next →](23-tool-schemas.md)

---

#### Summary

When building AI applications with Claude, you'll often need to give it access to real-time information or the ability to perform actions. This is where tool functions come in - they're Python functions that Claude can call when it needs additional data to help users.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623640%2F06_-_003_-_Tool_Functions_00.1748623639986.png)

The image above shows three essential tools we'll be implementing: getting the current date/time, adding duration to dates, and setting reminders. Let's start with the first one.

## What Are Tool Functions?

A tool function is a plain Python function that gets executed automatically when Claude decides it needs extra information to help a user. For example, if someone asks "What time is it?", Claude would call your date/time tool to get the current time.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623641%2F06_-_003_-_Tool_Functions_06.1748623640850.png)

Here's an example of a weather tool function. Notice how it validates inputs and provides clear error messages - these are important best practices.

## Best Practices for Tool Functions

When writing tool functions, follow these guidelines:

- **Use descriptive names:** Both your function name and parameter names should clearly indicate their purpose
- **Validate inputs:** Check that required parameters aren't empty or invalid, and raise errors when they are
- **Provide meaningful error messages:** Claude can see error messages and might retry the function call with corrected parameters

The validation is particularly important because Claude learns from errors. If you raise a clear error like "Location cannot be empty", Claude might try calling the function again with a proper location value.

## Building Your First Tool Function

Let's create a function to get the current date and time. This function will accept a date format parameter so Claude can request the time in different formats:

```
def get_current_datetime(date_format="%Y-%m-%d %H:%M:%S"):
    if not date_format:
        raise ValueError("date_format cannot be empty")
    return datetime.now().strftime(date_format)
```

This function uses Python's datetime module to get the current time and format it according to the provided format string. The default format gives us year-month-day hour:minute:second.

You can test it with different formats:

```
# Default format: "2024-01-15 14:30:25"
get_current_datetime()

# Just hour and minute: "14:30"
get_current_datetime("%H:%M")
```

The validation check ensures Claude can't pass an empty string for the date format. While this specific error is unlikely, it demonstrates the pattern of validating inputs and providing helpful error messages that Claude can learn from.

## Next Steps

Creating the function is just the first step. Next, you'll need to write a JSON schema that describes the function to Claude, then integrate it into your chat system. This tool function approach gives Claude powerful capabilities while keeping your code organized and maintainable.

#### Downloads

- [001_tools.ipynb](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762978090/001_tools.ipynb?response-content-disposition=attachment&Expires=1776658256&Signature=qc8~5JgAafPlqLP1xM1HQmoZykuHstbQHVzKh7f~F-8iR2AiRugK5cszCxbq8MXrWB6SHV7zvdgyu9CHn2BqJn1n1EXzXkV2~UnGkIGIDGIjOBKaAPQ5UyUeOwUNI0kwIxKEd-9ZKQJ2g1oFTLLU1-ry2Vm5bx9bmIoTHvQqdqwDSq0EXYXezWInboS~JN4Q6tywBDuokQm6JdtU25t-lG-~gtbvrS2vAFlEyHdlGQI3lYeU3FX4tbEJesg8ugiDJS5wMDIZGe8SflQY5UagGwE8G8grIoK~mAS1x5H2z5sEweP8HW7-hb8pAjr4zRXujxXk73eG1GjXXeRcihzQXg__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
