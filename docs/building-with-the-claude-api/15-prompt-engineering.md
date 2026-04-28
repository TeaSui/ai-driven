[↑ Curriculum index](README.md) · [← prev](14-code-based-grading.md) · [next →](16-being-clear-and-direct.md)

---

#### Summary

Prompt engineering is about taking a prompt you've written and improving it to get more reliable, higher-quality outputs. This process involves iterative refinement - starting with a basic prompt, evaluating its performance, then systematically applying engineering techniques to improve it.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623584%2F05_-_001_-_Prompt_Engineering_00.1748623584369.png)

## The Iterative Improvement Process

The approach follows a clear cycle that you can repeat until you achieve your desired results:

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623585%2F05_-_001_-_Prompt_Engineering_01.1748623585186.png)

1. **Set a goal** - Define what you want your prompt to accomplish
2. **Write an initial prompt** - Create a basic first attempt
3. **Evaluate the prompt** - Test it against your criteria
4. **Apply prompt engineering techniques** - Use specific methods to improve performance
5. **Re-evaluate** - Verify that your changes actually improved the results

You repeat the last two steps until you're satisfied with the performance. Each iteration should show measurable improvement in your evaluation scores.

## Setting Up Your Evaluation Pipeline

To demonstrate this process, we'll work with a practical example: creating a prompt that generates one-day meal plans for athletes. The prompt needs to take into account an athlete's height, weight, goals, and dietary restrictions, then produce a comprehensive meal plan.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623586%2F05_-_001_-_Prompt_Engineering_04.1748623585977.png)

The evaluation setup uses a `PromptEvaluator` class that handles dataset generation and model grading. When creating your evaluator instance, you can control concurrency with the `max_concurrent_tasks` parameter:

```
evaluator = PromptEvaluator(max_concurrent_tasks=5)
```

Start with a low concurrency value (like 3) to avoid rate limit errors. You can increase it if your API quota allows for faster processing.

## Generating Test Data

The evaluation system can automatically generate test cases based on your prompt requirements. You define what inputs your prompt needs:

```
dataset = evaluator.generate_dataset(
    task_description="Write a compact, concise 1 day meal plan for a single athlete",
    prompt_inputs_spec={
        "height": "Athlete's height in cm",
        "weight": "Athlete's weight in kg", 
        "goal": "Goal of the athlete",
        "restrictions": "Dietary restrictions of the athlete"
    },
    output_file="dataset.json",
    num_cases=3
)
```

Keep the number of test cases low (2-3) during development to speed up your iteration cycle. You can increase this for final validation.

## Writing Your Initial Prompt

Start with a simple, naive prompt to establish a baseline. Here's an example of a deliberately basic first attempt:

```
def run_prompt(prompt_inputs):
    prompt = f"""
What should this person eat?

- Height: {prompt_inputs["height"]}
- Weight: {prompt_inputs["weight"]}
- Goal: {prompt_inputs["goal"]}
- Dietary restrictions: {prompt_inputs["restrictions"]}
"""
    
    messages = []
    add_user_message(messages, prompt)
    return chat(messages)
```

This basic prompt will likely produce poor results, but it gives you a starting point to measure improvement against.

## Adding Evaluation Criteria

When running your evaluation, you can specify additional criteria that the grading model should consider:

```
results = evaluator.run_evaluation(
    run_prompt_function=run_prompt,
    dataset_file="dataset.json",
    extra_criteria="""
The output should include:
- Daily caloric total
- Macronutrient breakdown  
- Meals with exact foods, portions, and timing
"""
)
```

This helps ensure your prompt is evaluated against the specific requirements that matter for your use case.

## Analyzing Results

After running an evaluation, you'll get both a numerical score and a detailed HTML report. The report shows you exactly how each test case performed, including the model's reasoning for each score.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623586%2F05_-_001_-_Prompt_Engineering_18.1748623586518.png)

Don't be discouraged by low initial scores - a score of 2.3 out of 10 is typical for a first attempt. The goal is to see consistent improvement as you apply engineering techniques.

![](https://everpath-course-content.s3-accelerate.amazonaws.com/instructor%2Fa46l9irobhg0f5webscixp0bs%2Fpublic%2F1748623587%2F05_-_001_-_Prompt_Engineering_19.1748623587369.png)

The detailed evaluation report helps you understand exactly where your prompt is failing and what improvements are needed. Use this feedback to guide your next iteration.

## Next Steps

With your baseline established, you're ready to start applying specific prompt engineering techniques. Each technique you learn should result in measurable improvement in your evaluation scores, gradually transforming your basic prompt into a reliable, high-performing tool.

Remember that prompt engineering is an iterative process. The key is to make one change at a time, evaluate the impact, and build on what works. This systematic approach ensures you understand which techniques provide the most value for your specific use case.

#### Downloads

- [001_prompting.ipynb](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762977962/001_prompting.ipynb?response-content-disposition=attachment&Expires=1776652517&Signature=kWlqEv1vcS0NnbOJNJCRwdlr-zWCfoMMt-vQLVwYKpzggYHmVxyuJo02RC-I3340Pb2rC5cvbEUtY~zRGobTFLijq7x5VmSrU6dhIp9aJOTrarqSV2RUliKU85~YVkZD59eoiWA5He9wDZwoFLtLkyU~FMo-PMaiRsJyG6goKUyQ71RjK5n56oZqckt1HNmSVbtZ79KaI5UM7jTFFvUGjH1Y7EpxmRaLqdzqm2g~r7p4wcwIW~VHpAMiFaD2rSHtnewj2JmTf25-zX2lU7CrawEmpBynvUyiJ23cFKByvre2QLc9EsUlEreYsT19YNH95j6JQVSACQXCOkrqq~H5Pg__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
- [002_prompting_completed.ipynb](https://cc.sj-cdn.net/instructor/4hdejjwplbrm-anthropic/assets/1762977962/002_prompting_completed.ipynb?response-content-disposition=attachment&Expires=1776652517&Signature=rFoZvoDcvkdD1tMJsAGmGOPtxClr0gvlUijDJdOxgbK6S6VEIPvcKhjZ9fvfzk7VhUYuOz5whMxdCvUX794tP5uBsu9BQdoUCex1IYUaL9BHUSfZnI13RP63dj1onibIxNMHMlZL6CS3u-IwahWr-4116NA7KwwjMLwQUxIWujDGrSyg7P3w4r6J0sfe-tCL3gHV0GyaokRjQRHLmiBN-4w3gE~AUwG1-NDy9pbIPWQBABneFr78W~cDbZSJKBXFu7JyaDWrMlXQd3ufooTJ5Yt0A7XOqqlQ0Ypx5LRaWk-7JZEQ7X~2Q0ZImWTuMgYlzP8C6Ti96-0IWv24q20a7w__&Key-Pair-Id=APKAI3B7HFD2VYJQK4MQ)
