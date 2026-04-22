// Main — mounts the design canvas and each artboard's screen inside.

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "theme": "light",
  "accentKey": "teal",
  "density": "balanced",
  "sidebarCollapsed": false,
  "showThinking": true
}/*EDITMODE-END*/;

function App() {
  const [theme, setTheme] = React.useState(TWEAK_DEFAULTS.theme);
  const [accentKey, setAccentKey] = React.useState(TWEAK_DEFAULTS.accentKey);
  const [density, setDensity] = React.useState(TWEAK_DEFAULTS.density);
  const [collapsed, setCollapsed] = React.useState(TWEAK_DEFAULTS.sidebarCollapsed);
  const [showThinking, setShowThinking] = React.useState(TWEAK_DEFAULTS.showThinking);

  const ctx = React.useMemo(() => ({
    theme, setTheme,
    accentKey, setAccentKey,
    accent: ACCENTS[accentKey] || ACCENTS.teal,
    density, setDensity,
    collapsed, setCollapsed,
    showThinking, setShowThinking,
  }), [theme, accentKey, density, collapsed, showThinking]);

  return (
    <GtxCtx.Provider value={ctx}>
      <DesignCanvas>
        <DCSection id="overview" title="GoTyme-X AI" subtitle="AI-driven SDLC platform · 7 connected surfaces">
          <DCArtboard id="hero" label="00 · Overview" width={1320} height={820}>
            <OverviewScreen/>
          </DCArtboard>
          <DCArtboard id="timeline" label="01 · Unified timeline" width={1320} height={820}>
            <TimelineScreen/>
          </DCArtboard>
          <DCArtboard id="agent" label="02 · Coding Agent (live)" width={1320} height={820}>
            <CodingAgentScreen showThinking={showThinking}/>
          </DCArtboard>
          <DCArtboard id="review" label="03 · AI Code Review" width={1320} height={820}>
            <CodeReviewScreen/>
          </DCArtboard>
          <DCArtboard id="devops" label="04 · DevOps · incident triage" width={1320} height={820}>
            <DevOpsScreen/>
          </DCArtboard>
          <DCArtboard id="metrics" label="05 · Metrics" width={1320} height={820}>
            <MetricsScreen/>
          </DCArtboard>
          <DCArtboard id="slack" label="06 · Slack bot" width={1320} height={820}>
            <SlackScreen/>
          </DCArtboard>
        </DCSection>
      </DesignCanvas>
      <TweaksPanel/>
    </GtxCtx.Provider>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App/>);
