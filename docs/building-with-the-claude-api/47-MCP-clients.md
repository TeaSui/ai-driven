# MCP Clients

The MCP client serves as the communication bridge between your server and MCP servers. Think of it as your access point to all the tools that an MCP server provides. When you need to use external tools or services, the client handles all the message passing and protocol details for you.

## Transport Agnostic Communication

One of MCP's key strengths is being transport agnostic - a fancy way of saying the client and server can talk to each other using different communication methods. The most common setup runs both the MCP client and server on the same machine, where they communicate through standard input/output.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542644%2F09_-_002_-_MCP_Clients_01.1748542644766.jpg)

But you're not limited to that approach. MCP clients and servers can also connect over:

- HTTP
- WebSockets
- Various other network protocols

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542645%2F09_-_002_-_MCP_Clients_03.1748542645442.jpg)

## Message Types

Once connected, the client and server exchange specific message types defined in the MCP specification. The main message types you'll work with are:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542645%2F09_-_002_-_MCP_Clients_04.1748542645814.jpg)

**ListToolsRequest/ListToolsResult:** The client asks the server "what tools do you provide?" and gets back a list of available tools.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542646%2F09_-_002_-_MCP_Clients_05.1748542646228.jpg)

**CallToolRequest/CallToolResult:** The client asks the server to run a specific tool with certain arguments, then receives the results.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542646%2F09_-_002_-_MCP_Clients_06.1748542646636.jpg)

## Complete Flow Example

Here's how all the pieces work together in a real scenario. Let's say a user asks "What repositories do I have?" - here's the complete communication flow:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542647%2F09_-_002_-_MCP_Clients_08.1748542647060.jpg)

The process starts when a user submits a query to your server. Your server realizes it needs to provide Claude with a list of available tools before making the request.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542648%2F09_-_002_-_MCP_Clients_09.1748542647956.jpg)

Your server asks the MCP client for tools, which sends a `ListToolsRequest` to the MCP server and receives a `ListToolsResult` back.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542648%2F09_-_002_-_MCP_Clients_11.1748542648353.jpg)

Now your server has everything needed to make the initial request to Claude - both the user's question and the available tools.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542649%2F09_-_002_-_MCP_Clients_12.1748542648890.jpg)

Claude examines the tools and decides it needs to call one to answer the question. It responds with a tool use request.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542649%2F09_-_002_-_MCP_Clients_14.1748542649299.jpg)

Your server asks the MCP client to execute the tool Claude requested. The MCP client sends a `CallToolRequest` to the MCP server, which then makes the actual request to GitHub.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542649%2F09_-_002_-_MCP_Clients_15.1748542649806.jpg)

GitHub returns the repository data, which flows back through the MCP server as a `CallToolResult`, then to the MCP client, and finally to your server.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542650%2F09_-_002_-_MCP_Clients_17.1748542650158.jpg)

Your server sends the tool results back to Claude in a follow-up message. Claude now has all the information it needs to formulate a complete response.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542651%2F09_-_002_-_MCP_Clients_18.1748542650970.jpg)

Finally, Claude responds with the formatted answer, which your server passes back to the user.

Yes, this flow involves many steps, but each component has a clear responsibility. The MCP client abstracts away the complexity of server communication, letting you focus on building your application logic. As we implement our own MCP client and server, you'll see how each piece fits together in practice.
