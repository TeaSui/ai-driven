package agent

import (
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

func newClassifierForTest() *CommentIntentClassifier {
	return NewCommentIntentClassifier("ai")
}

func TestClassify_EmptyBody(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentIrrelevant, c.Classify("", false))
	assert.Equal(t, model.IntentIrrelevant, c.Classify("   ", false))
}

func TestClassify_BotAuthor(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentIrrelevant, c.Classify("@ai do something", true))
}

func TestClassify_BotSignature(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentIrrelevant, c.Classify("@ai \U0001f916 processing your request", false))
	assert.Equal(t, model.IntentIrrelevant, c.Classify("@ai _\U0001f916 ai agent response", false))
	assert.Equal(t, model.IntentIrrelevant, c.Classify("@ai | tools: search_code", false))
}

func TestClassify_NoMention(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentIrrelevant, c.Classify("please fix the login bug", false))
	assert.Equal(t, model.IntentIrrelevant, c.Classify("looks good to me", false))
}

func TestClassify_RejectionKeywords(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentApproval, c.Classify("@ai reject this change", false))
	assert.Equal(t, model.IntentApproval, c.Classify("@ai cancel", false))
	assert.Equal(t, model.IntentApproval, c.Classify("@ai don't do it", false))
	assert.Equal(t, model.IntentApproval, c.Classify("@ai abort", false))
}

func TestClassify_ApprovalKeywords(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentApproval, c.Classify("@ai lgtm", false))
	assert.Equal(t, model.IntentApproval, c.Classify("@ai approved", false))
	assert.Equal(t, model.IntentApproval, c.Classify("@ai go ahead", false))
	assert.Equal(t, model.IntentApproval, c.Classify("@ai ship it", false))
	assert.Equal(t, model.IntentApproval, c.Classify("@ai looks good", false))
}

func TestClassify_FeedbackKeywords(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentHumanFeedback, c.Classify("@ai please fix the null check", false))
	assert.Equal(t, model.IntentHumanFeedback, c.Classify("@ai doesn't work on edge cases", false))
	assert.Equal(t, model.IntentHumanFeedback, c.Classify("@ai try again with a different approach", false))
	assert.Equal(t, model.IntentHumanFeedback, c.Classify("@ai add tests for the handler", false))
	assert.Equal(t, model.IntentHumanFeedback, c.Classify("@ai refactor the service layer", false))
}

func TestClassify_QuestionMark(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentQuestion, c.Classify("@ai is this thread safe?", false))
	assert.Equal(t, model.IntentQuestion, c.Classify("@ai can this fail?", false))
}

func TestClassify_QuestionStarters(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentQuestion, c.Classify("@ai why does this fail", false))
	assert.Equal(t, model.IntentQuestion, c.Classify("@ai how does the auth work", false))
	assert.Equal(t, model.IntentQuestion, c.Classify("@ai what is the purpose of this", false))
	assert.Equal(t, model.IntentQuestion, c.Classify("@ai can you explain this logic", false))
	assert.Equal(t, model.IntentQuestion, c.Classify("@ai is there a better way", false))
}

func TestClassify_DefaultCommand(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentAICommand, c.Classify("@ai implement the caching layer", false))
	assert.Equal(t, model.IntentAICommand, c.Classify("@ai create a new endpoint for users", false))
}

func TestClassify_CustomMentionKeyword(t *testing.T) {
	c := NewCommentIntentClassifier("bot")

	assert.Equal(t, model.IntentIrrelevant, c.Classify("@ai do something", false))
	assert.Equal(t, model.IntentAICommand, c.Classify("@bot implement feature", false))
}

func TestClassify_CaseInsensitiveMention(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, model.IntentAICommand, c.Classify("@AI implement feature", false))
	assert.Equal(t, model.IntentAICommand, c.Classify("@Ai implement feature", false))
}

func TestStripMention(t *testing.T) {
	c := newClassifierForTest()

	assert.Equal(t, "do something", c.StripMention("@ai do something"))
	assert.Equal(t, "do something", c.StripMention("[~accountId:12345] @ai do something"))
	assert.Equal(t, "hello", c.StripMention("@ai hello"))
	assert.Equal(t, "", c.StripMention(""))
}

func TestStripMention_JiraAndBotMention(t *testing.T) {
	c := newClassifierForTest()

	result := c.StripMention("[~accountId:abc123] @ai hello")
	assert.Equal(t, "hello", result)
}

func TestStripMention_JiraMentionOnly(t *testing.T) {
	c := newClassifierForTest()

	result := c.StripMention("[~accountId:abc123] do something")
	assert.Equal(t, "do something", result)
}
