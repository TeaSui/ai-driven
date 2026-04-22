// Icons — minimal stroke set. 16px viewBox, use size prop.
const Ic = {};

function mk(path, viewBox = '0 0 16 16') {
  return function Icon({ size = 14, color = 'currentColor', style, strokeWidth = 1.5 }) {
    return (
      <svg width={size} height={size} viewBox={viewBox} fill="none"
        stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round"
        style={{ display: 'inline-block', flexShrink: 0, ...style }}>
        {path}
      </svg>
    );
  };
}

Ic.Chevron      = mk(<path d="M6 4l4 4-4 4" />);
Ic.ChevronDown  = mk(<path d="M4 6l4 4 4-4" />);
Ic.ChevronLeft  = mk(<path d="M10 4l-4 4 4 4" />);
Ic.ChevronUp    = mk(<path d="M4 10l4-4 4 4" />);
Ic.ArrowRight   = mk(<><path d="M3 8h10"/><path d="M9 4l4 4-4 4"/></>);
Ic.ArrowUp      = mk(<><path d="M8 13V3"/><path d="M4 7l4-4 4 4"/></>);
Ic.Plus         = mk(<><path d="M8 3v10"/><path d="M3 8h10"/></>);
Ic.Close        = mk(<><path d="M4 4l8 8"/><path d="M12 4l-8 8"/></>);
Ic.Check        = mk(<path d="M3 8l3.5 3.5L13 5"/>);
Ic.Dot          = mk(<circle cx="8" cy="8" r="1.5" />);
Ic.More         = mk(<><circle cx="3.5" cy="8" r="1"/><circle cx="8" cy="8" r="1"/><circle cx="12.5" cy="8" r="1"/></>);
Ic.Search       = mk(<><circle cx="7" cy="7" r="4.5"/><path d="M13 13l-2.7-2.7"/></>);
Ic.Filter       = mk(<path d="M2 3h12l-4.5 6V13l-3 1V9L2 3z" />);
Ic.Sparkle      = mk(<><path d="M8 1.5l1.6 4L13.5 7l-3.9 1.5L8 12.5 6.4 8.5 2.5 7l3.9-1.5L8 1.5z"/></>);
Ic.Bolt         = mk(<path d="M8.5 1.5L3 9h4l-.5 5.5L12 7H8l.5-5.5z" />);
Ic.Bot          = mk(<><rect x="3" y="5" width="10" height="8" rx="2"/><circle cx="6" cy="9" r=".8" fill="currentColor"/><circle cx="10" cy="9" r=".8" fill="currentColor"/><path d="M8 3v2"/><path d="M1 9h2"/><path d="M13 9h2"/></>);
Ic.Git          = mk(<><circle cx="4" cy="4" r="1.5"/><circle cx="12" cy="4" r="1.5"/><circle cx="4" cy="12" r="1.5"/><path d="M4 5.5v5"/><path d="M12 5.5c0 4-4 3-8 5"/></>);
Ic.GitBranch    = mk(<><circle cx="4" cy="3.5" r="1.2"/><circle cx="4" cy="12.5" r="1.2"/><circle cx="12" cy="6.5" r="1.2"/><path d="M4 4.7v6.6"/><path d="M11 6.5c-3.5 0-7 .5-7 6"/></>);
Ic.GitPr        = mk(<><circle cx="4" cy="3.5" r="1.2"/><circle cx="4" cy="12.5" r="1.2"/><circle cx="12" cy="12.5" r="1.2"/><path d="M4 4.7v6.6"/><path d="M12 11.3V7a2 2 0 00-2-2H7.5"/><path d="M9 3l-1.5 2L9 7"/></>);
Ic.GitCommit    = mk(<><circle cx="8" cy="8" r="2.5"/><path d="M2 8h3"/><path d="M11 8h3"/></>);
Ic.GitMerge     = mk(<><circle cx="4" cy="3.5" r="1.2"/><circle cx="4" cy="12.5" r="1.2"/><circle cx="12" cy="8" r="1.2"/><path d="M4 4.7v6.6"/><path d="M4.5 6c1 2 2 2 6 2"/></>);
Ic.Jira         = mk(<><rect x="2.5" y="2.5" width="11" height="11" rx="1"/><path d="M5 8l2.2 2.2L11 6.5"/></>);
Ic.Github       = mk(<><path d="M8 1.5c-3.6 0-6.5 2.9-6.5 6.5 0 2.9 1.9 5.3 4.4 6.2.3.1.4-.1.4-.3v-1.2c-1.8.4-2.2-.8-2.2-.8-.3-.8-.7-1-.7-1-.6-.4 0-.4 0-.4.7 0 1 .7 1 .7.6 1 1.6.7 2 .5.1-.4.2-.7.4-.9-1.4-.2-2.9-.7-2.9-3.2 0-.7.3-1.3.7-1.7-.1-.2-.3-.9.1-1.8 0 0 .6-.2 1.8.7a6 6 0 013.3 0c1.2-.8 1.8-.7 1.8-.7.4.9.1 1.6.1 1.8.4.4.7 1 .7 1.7 0 2.5-1.5 3-2.9 3.2.2.2.4.6.4 1.2v1.8c0 .2.1.4.4.3 2.5-.9 4.4-3.3 4.4-6.2 0-3.6-2.9-6.5-6.5-6.5z" fill="currentColor" stroke="none"/></>);
Ic.Slack        = mk(<><rect x="6.5" y="1.5" width="3" height="6" rx="1.5"/><rect x="6.5" y="8.5" width="3" height="6" rx="1.5"/><rect x="1.5" y="6.5" width="6" height="3" rx="1.5"/><rect x="8.5" y="6.5" width="6" height="3" rx="1.5"/></>);
Ic.Aws          = mk(<><path d="M2 6c0 2.5 2.5 4.5 6 4.5S14 8.5 14 6"/><path d="M4 10.5c1 1 2.5 2 4 2s3-1 4-2"/><ellipse cx="8" cy="4" rx="4" ry="1.8"/></>);
Ic.Datadog      = mk(<><path d="M2 8l4.5-4.5 3 3L12 4v5l-3 3-3-3L3 11"/></>);
Ic.File         = mk(<><path d="M4 2h5l3 3v9H4z"/><path d="M9 2v3h3"/></>);
Ic.Folder       = mk(<path d="M2 4a1 1 0 011-1h3l2 2h5a1 1 0 011 1v6a1 1 0 01-1 1H3a1 1 0 01-1-1z"/>);
Ic.Terminal     = mk(<><rect x="1.5" y="3" width="13" height="10" rx="1.5"/><path d="M4 7l2 1.5L4 10"/><path d="M8 10h3"/></>);
Ic.Settings     = mk(<><circle cx="8" cy="8" r="2"/><path d="M13.5 8a5.5 5.5 0 00-.1-1l1.3-1-1.3-2.2-1.6.5A5.5 5.5 0 0010.5 3L10 1.4H6L5.5 3a5.5 5.5 0 00-1.3.8l-1.6-.5L1.3 5.5l1.3 1A5.5 5.5 0 002.5 8v1l-1.3 1 1.3 2.2 1.6-.5c.4.3.8.6 1.3.8L6 14.6h4l.5-1.6c.5-.2.9-.5 1.3-.8l1.6.5 1.3-2.2-1.3-1v-1z"/></>);
Ic.User         = mk(<><circle cx="8" cy="5.5" r="2.5"/><path d="M3 14c0-2.8 2.2-5 5-5s5 2.2 5 5"/></>);
Ic.Team         = mk(<><circle cx="5.5" cy="6" r="2"/><circle cx="11" cy="6.5" r="1.5"/><path d="M2 13c0-2 1.5-3.5 3.5-3.5S9 11 9 13"/><path d="M9.5 11c.5-1 1.5-1.5 2.5-1.5 1.5 0 3 1 3 3"/></>);
Ic.Play         = mk(<path d="M5 3l8 5-8 5V3z" fill="currentColor"/>);
Ic.Pause        = mk(<><rect x="4" y="3" width="3" height="10" fill="currentColor"/><rect x="9" y="3" width="3" height="10" fill="currentColor"/></>);
Ic.Clock        = mk(<><circle cx="8" cy="8" r="6"/><path d="M8 4.5V8l2.5 1.5"/></>);
Ic.Zap          = mk(<path d="M8.5 1.5L3 9h4l-.5 5.5L12 7H8l.5-5.5z"/>);
Ic.Bell         = mk(<><path d="M3.5 11c0-5 1.5-7 4.5-7s4.5 2 4.5 7"/><path d="M2 11h12"/><path d="M6.5 13a1.5 1.5 0 003 0"/></>);
Ic.Alert        = mk(<><path d="M8 2l6.5 11H1.5L8 2z"/><path d="M8 6.5v3.5"/><circle cx="8" cy="11.5" r=".8" fill="currentColor"/></>);
Ic.Shield       = mk(<path d="M8 1.5L13.5 4v4c0 3.5-2.5 5.5-5.5 6.5-3-1-5.5-3-5.5-6.5V4L8 1.5z"/>);
Ic.Eye          = mk(<><path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8z"/><circle cx="8" cy="8" r="2"/></>);
Ic.Comment      = mk(<path d="M2.5 3.5h11v7h-6L4.5 13v-2.5h-2v-7z"/>);
Ic.Check2       = mk(<path d="M2 8l3 3L14 3"/>);
Ic.DoubleCheck  = mk(<><path d="M1 8l3 3 5-5"/><path d="M7 11l2 2 5-5"/></>);
Ic.X            = mk(<><path d="M4 4l8 8"/><path d="M12 4l-8 8"/></>);
Ic.ArrowUpRight = mk(<><path d="M5 11L11 5"/><path d="M6 5h5v5"/></>);
Ic.Refresh      = mk(<><path d="M13 8a5 5 0 11-1.5-3.5"/><path d="M13 2v3h-3"/></>);
Ic.Sun          = mk(<><circle cx="8" cy="8" r="3"/><path d="M8 1v1.5M8 13.5V15M1 8h1.5M13.5 8H15M3 3l1 1M12 12l1 1M3 13l1-1M12 4l1-1"/></>);
Ic.Moon         = mk(<path d="M13 10a6 6 0 01-7-7 6 6 0 107 7z"/>);
Ic.Code         = mk(<><path d="M5 4l-3 4 3 4"/><path d="M11 4l3 4-3 4"/></>);
Ic.Database     = mk(<><ellipse cx="8" cy="3.5" rx="5" ry="1.5"/><path d="M3 3.5v9c0 .8 2.2 1.5 5 1.5s5-.7 5-1.5v-9"/><path d="M3 8c0 .8 2.2 1.5 5 1.5s5-.7 5-1.5"/></>);
Ic.Menu         = mk(<><path d="M2 4h12"/><path d="M2 8h12"/><path d="M2 12h12"/></>);
Ic.Panel        = mk(<><rect x="2" y="3" width="12" height="10" rx="1"/><path d="M6 3v10"/></>);
Ic.Thinking     = mk(<><circle cx="3" cy="8" r="1" fill="currentColor"/><circle cx="8" cy="8" r="1" fill="currentColor"/><circle cx="13" cy="8" r="1" fill="currentColor"/></>);
Ic.Wand         = mk(<><path d="M11 2l1 1-8 8-1-1 8-8z"/><path d="M12 5l1-1"/><path d="M3 10l1-1"/><path d="M13 11l1-1M14 8l1 0"/></>);

Object.assign(window, { Ic });
