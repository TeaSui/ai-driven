package agent

import (
	"regexp"
	"strings"

	"github.com/rs/zerolog/log"

	"github.com/AirdropToTheMoon/ai-driven/internal/agent/model"
)

var (
	jiraMentionPattern = regexp.MustCompile(`\[~accountId:[^\]]+\]`)

	botSignaturePatterns = []string{
		"\U0001f916 processing your request",
		"working on it \u2014 this comment will be updated",
		"_\U0001f916 ai agent",
		"| tools:",
		"| tokens:",
		"\U0001f916 \u274c *error processing request*",
		"{quote}\U0001f916",
		"{quote}\\{quote}",
	}

	approvalKeywords = []string{
		"lgtm", "approved", "approve", "proceed", "go ahead", "ship it",
		"merge it", "looks good", "accept", "confirm", "yes do it", "yes, do it",
	}

	rejectionKeywords = []string{
		"reject", "cancel", "don't do it", "abort", "stop", "no don't", "no, don't",
	}

	feedbackKeywords = []string{
		"doesn't work", "doesn't handle", "should also", "please fix",
		"incorrect", "wrong", "missing", "instead of", "change this",
		"update this", "revert", "not what i", "try again",
		"use optional", "use a different", "better approach", "refactor",
		"rename", "remove the", "add a test", "add tests", "add error handling",
		"handle the case", "what about", "you forgot", "also need",
	}

	questionStarters = []string{
		"why", "how", "what", "when", "where", "which", "who",
		"can you explain", "could you explain", "is there", "are there",
		"do you", "does this", "will this",
	}
)

type CommentIntentClassifier struct {
	mentionPattern *regexp.Regexp
	aiClient       AiClient
	useLLMFallback bool
}

func NewCommentIntentClassifier(mentionKeyword string) *CommentIntentClassifier {
	return newClassifier(mentionKeyword, nil, false)
}

func NewCommentIntentClassifierWithLLM(mentionKeyword string, aiClient AiClient) *CommentIntentClassifier {
	return newClassifier(mentionKeyword, aiClient, true)
}

func newClassifier(mentionKeyword string, aiClient AiClient, useLLMFallback bool) *CommentIntentClassifier {
	kw := strings.TrimSpace(strings.ToLower(mentionKeyword))
	if kw == "" {
		kw = "ai"
	}
	pattern := regexp.MustCompile(`(?i)@` + regexp.QuoteMeta(kw) + `\b`)
	return &CommentIntentClassifier{
		mentionPattern: pattern,
		aiClient:       aiClient,
		useLLMFallback: useLLMFallback && aiClient != nil,
	}
}

func (c *CommentIntentClassifier) Classify(commentBody string, authorIsBot bool) model.CommentIntent {
	if strings.TrimSpace(commentBody) == "" {
		return model.IntentIrrelevant
	}

	if authorIsBot {
		log.Debug().Msg("Ignoring bot-authored comment")
		return model.IntentIrrelevant
	}

	trimmed := strings.TrimSpace(commentBody)
	lowerBody := strings.ToLower(trimmed)

	for _, signature := range botSignaturePatterns {
		if strings.Contains(lowerBody, signature) {
			log.Debug().Str("signature", signature).Msg("Ignoring comment with bot signature")
			return model.IntentIrrelevant
		}
	}

	if !c.mentionPattern.MatchString(trimmed) {
		return model.IntentIrrelevant
	}

	body := strings.ToLower(strings.TrimSpace(c.StripMention(trimmed)))

	for _, keyword := range rejectionKeywords {
		if strings.Contains(body, keyword) {
			log.Info().Str("keyword", keyword).Msg("Classified as APPROVAL (rejection) via keyword")
			return model.IntentApproval
		}
	}

	for _, keyword := range approvalKeywords {
		if strings.Contains(body, keyword) {
			log.Info().Str("keyword", keyword).Msg("Classified as APPROVAL via keyword")
			return model.IntentApproval
		}
	}

	for _, keyword := range feedbackKeywords {
		if strings.Contains(body, keyword) {
			log.Info().Str("keyword", keyword).Msg("Classified as HUMAN_FEEDBACK via keyword")
			return model.IntentHumanFeedback
		}
	}

	if strings.HasSuffix(body, "?") {
		return model.IntentQuestion
	}

	for _, starter := range questionStarters {
		if strings.HasPrefix(body, starter) {
			return model.IntentQuestion
		}
	}

	return model.IntentAICommand
}

func (c *CommentIntentClassifier) StripMention(commentBody string) string {
	if commentBody == "" {
		return ""
	}
	stripped := strings.TrimSpace(commentBody)
	stripped = strings.TrimSpace(jiraMentionPattern.ReplaceAllString(stripped, ""))
	stripped = strings.TrimSpace(c.mentionPattern.ReplaceAllStringFunc(stripped, func(_ string) string {
		return ""
	}))
	return stripped
}
