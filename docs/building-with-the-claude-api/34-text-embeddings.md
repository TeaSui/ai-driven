# Text Embeddings

After breaking a document into chunks, the next step in a RAG pipeline is finding which chunks are most relevant to a user's question. This is essentially a search problem - you need to look through all your text chunks and identify the ones that relate to what the user is asking about.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542211%2F07_-_003_-_Text_Embeddings_03.1748542211434.jpg)

## Semantic Search

The most common approach for finding relevant chunks is semantic search. Unlike keyword-based search that looks for exact word matches, semantic search uses text embeddings to understand the meaning and context of both the user's question and each text chunk.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542212%2F07_-_003_-_Text_Embeddings_04.1748542212115.jpg)

## Text Embeddings

A text embedding is a numerical representation of the meaning contained in some text. Think of it as converting words and sentences into a format that computers can work with mathematically.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542212%2F07_-_003_-_Text_Embeddings_07.1748542212576.jpg)

Here's how the process works:

- You feed text into an embedding model
- The model outputs a long list of numbers (the embedding)
- Each number ranges from -1 to +1
- These numbers represent different qualities or features of the input text

## Understanding the Numbers

Each number in an embedding is essentially a "score" for some quality of the input text. However, here's the important caveat: we don't know precisely what each number represents.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542213%2F07_-_003_-_Text_Embeddings_09.1748542213029.jpg)

While it's helpful to imagine that one number might represent "how happy the text is" or "how much the text talks about oceans," these are just conceptual examples. The actual meaning of each dimension is learned by the model during training and isn't directly interpretable by humans.

## VoyageAI for Embeddings

Since Anthropic doesn't currently provide embedding generation, the recommended provider is VoyageAI. You'll need to:

- Sign up for a separate VoyageAI account
- Get an API key (free to get started)
- Add the key to your environment variables

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542213%2F07_-_003_-_Text_Embeddings_15.1748542213569.jpg)

In your `.env` file, add:

```python
VOYAGE_API_KEY="your_key_here"
```

## Implementation

First, install the VoyageAI library:

```python
%pip install voyageai
```

Then set up the client and create a function to generate embeddings:

```python
from dotenv import load_dotenv
import voyageai

load_dotenv()
client = voyageai.Client()

def generate_embedding(text, model="voyage-3-large", input_type="query"):
    result = client.embed([text], model=model, input_type=input_type)
    return result.embeddings[0]
```

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542214%2F07_-_003_-_Text_Embeddings_18.1748542214005.jpg)

When you run this function on a text chunk, you'll get back a list of floating-point numbers representing the embedding. The process is quick and straightforward - the real challenge is understanding how to use these embeddings effectively in your RAG pipeline for finding the most relevant content.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748542214%2F07_-_003_-_Text_Embeddings_19.1748542214605.jpg)

The next step is learning how to compare embeddings to determine which chunks are most similar to a user's question, which forms the core of the semantic search process.

## Downloads

- [002_embeddings.ipynb](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic-poc/assets/1748558530/002_embeddings.ipynb?response-content-disposition=attachment&Expires=1776658819&Signature=MzxigJG2hedbZtnvpusFXhtitknGHPBUgbkPHMFTXU3p6TcDBtesePDOHRsi0H~zQx3xJeXy6yS5XDzQqBRhh2y~AFjqImZiC3nJysXSH2Jsar0qQqTrcXAIsVuqyy3CXWBeJTyZnIe02WMUOPxvnSox5sgQnESgej8tMuoXTJDEvyf~hCilsmK-N-82vip9~F9D0DN7-B-T4d-0Byy3kiGM6pX7UXNRy7HvNJepL62HMJBiGgXomeuM4BWsFUQc38O7fH6A6cCxt2mX-mSP1mtb0TNUutrOxh0p-N0irO06MdfgxslwvJ97lWRT9gwZbLjRekFWaieY3aAajYJ3Ow__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
- [VoyageAI API Key Directions.pdf](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic-poc/assets/1748558581/VoyageAI_API_Key_Directions.pdf?response-content-disposition=attachment&Expires=1776658819&Signature=CYCrRZVD7Kx5tWKgxDln36oYA6mQc~bm7ylE5X369Se3PhBs~t3JkTNlBkXbrOug35qraj8-g~0NHyAOiw5WMjldwjNy7fOzscIWTx2c8urYdctqBXV2rK1JbPkO2G-c7DlY~-OqzfBMr0w7fBhQBntVt2ADXST5LosvDITs1YsHmvbBNQQNpR70EUyGTEHRENWbqFr60SVakRFXldrD9CeoHRhn7NfVmkRxpxXlPeXQi6KH2nWiVzFwEGjiTR3Y6W7RdMEIaCIf~k4TToU1yGbolfFEVhlVEoJSYfCyfz-10h96OnK0nmZt~~SFY-xSFQg3QrDsweAxH1b-RDewSA__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
