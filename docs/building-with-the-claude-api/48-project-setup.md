[↑ Curriculum index](README.md) · [← prev](47-MCP-clients.md) · [next →](49-the-server-inspector.md)

---

#### Summary

We're going to build a CLI-based chatbot to better understand how MCP clients and servers work together. This hands-on project will give you practical experience with both sides of the MCP architecture.

## What We're Building

Our chatbot will allow users to interact with a collection of documents through a command-line interface. The system consists of two main components:

- An MCP client that handles user interactions
- A custom MCP server that manages document operations

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542682%2F09_-_003_-_Project_Setup_03.1748542682857.jpg)

The server will provide two essential tools: one for reading document contents and another for updating them. All documents will be stored in memory for simplicity - no database required.

## Important Architecture Note

In real-world projects, you typically implement either an MCP client or an MCP server, not both. You might create:

- An MCP server to expose your service to other developers
- An MCP client to connect to existing MCP servers

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542683%2F09_-_003_-_Project_Setup_07.1748542683236.jpg)

We're building both components in this project purely for educational purposes - to understand how they communicate and work together.

## Project Setup

Download the `cli_project.zip` file attached to this lesson and extract it to your preferred development directory. Open your code editor in the project folder.

The project includes a comprehensive README file with setup instructions. Follow these steps:

1. Add your Anthropic API key to the `.env` file
2. Install dependencies using either UV (recommended) or pip
3. Run the starter application to verify everything works

## Running the Application

Navigate to your project directory in the terminal. You'll see the main project files including `main.py`, `mcp_client.py`, and `mcp_server.py`.

To start the application, use one of these commands:

```
# If using UV (recommended)
uv run main.py

# If using standard Python
python main.py
```

When the application starts successfully, you'll see a chat prompt. Test it by asking a simple question like "what's 1+1?" - you should get a quick response from Claude.

With the basic setup complete, we're ready to start implementing MCP features and exploring how clients and servers communicate through the Model Control Protocol.

#### Downloads

- [cli_project.zip](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762981524/cli_project.zip?response-content-disposition=attachment&Expires=1776659535&Signature=XNduHZJ6JfTE3cn2wJRNrlqxsFk0PE2V8poYt5UBLxNEBX3B9rspDhTGlYXFk4ZwWavIKU9wvWm5amF4X83uBDZMbx48L8y-bW9AC97GBwwMOgiC5iPIGKYQyiipbh55cVdieyQT99ILhfFVtxGuf8tdTiOFzB3D3zmSXAnH39u7P9Ffa3spa5Xw89BcrNTo8O1qYrNcDcwJKrpUzE79zgPetk55N4UwzmxesFm98zJbCQ2UilKIv7cGhScdh5jxMwRZGR0Cj36llQ2U3EfPuULTbeHaB~MVPH51U-z8jyVtjIl1f4lfBEOVFzheCO7MUR4RK81X7~fKi1bIL~ngjA__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
- [cli_project_COMPLETE.zip](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762981524/cli_project_COMPLETE.zip?response-content-disposition=attachment&Expires=1776659535&Signature=LfoWkno1Cuzt11idSsMswfUrw2VgnBkQQypJMeSsApdRfnViShreUHekEI9baaf6yv8ii~cgjl2rDxtPqo7rjTgSyh8Lg0Bv1JiUTXv1ZSm90GxeJUnTlNmxIGgmbSFt~1ZuhEZBKhjyLm253K8AHZCJSbX0mCXRfb89zEhf3c0Mj2-Aw8yaljDgoBqLu~u8DRYpRIPBbinw7B4TrLviXMOlRi~mOibuR5oN-ZvBgCy0Yf1XlM50W7IDIew83TJFRPcRFIUEMe7eI9Y1qdKOd5x9NAa-K0jnJirvP0CViRkizv6JS8xuMhza8NVrf4ztELOPTKlAQur7Mvc4aHJzLg__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
