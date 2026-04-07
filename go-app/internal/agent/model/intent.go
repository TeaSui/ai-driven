package model

// CommentIntent classifies the intent of an incoming comment.
type CommentIntent string

const (
	IntentAICommand     CommentIntent = "AI_COMMAND"
	IntentHumanFeedback CommentIntent = "HUMAN_FEEDBACK"
	IntentQuestion      CommentIntent = "QUESTION"
	IntentApproval      CommentIntent = "APPROVAL"
	IntentReview        CommentIntent = "REVIEW"
	IntentTest          CommentIntent = "TEST"
	IntentIrrelevant    CommentIntent = "IRRELEVANT"
)
