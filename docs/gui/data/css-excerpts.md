# 11 rule (s) matching 'container__8a031'

.container__8a031, .outerContainer__8a031 {
box-sizing: border-box;
display: flex;
position: relative;
}

.container__8a031 {
--custom-border-radius: var (--radius-md);
background: var (--background-surface-high);
border: 1px solid var (--border-subtle);
border-radius: var (--custom-border-radius);
box-shadow: var (--shadow-high);
color: var (--text-default);
flex-direction: column;
max-height: 100%;
pointer-events: auto;
width: 100%;
}

/*  @ (max-height: 550px) and (pointer: coarse), (max-width: 485px) */
.fullScreenOnMobile__8a031 .container__8a031 {
--custom-border-radius: 0;
--custom-modal-padding-top: calc (var (--custom-app-top-bar-height) + var (--custom-modal-padding-sm));
border-width: medium;
border-style: none;
border-color: currentcolor;
border-image: none;
height: 100%;
overflow-y: auto;
}

/*  @ (max-height: 550px) */
.container__8a031 {
overflow-y: auto;
}

/*  @ (max-height: 550px) */
.container__8a031 .body__8a031 {
flex-shrink: 0;
scrollbar-gutter: auto;
}

/*  @ (max-height: 550px) */
.container__8a031 .body__8a031::-webkit-scrollbar {
width: 0px;
}

/*  @ (max-height: 550px) */
.container__8a031 .headerGradient__8a031::before {
left: 0px;
right: 0px;
top: calc (var (--custom-modal-padding-top)*-1);
}

.container__8a031 > .bodyScrollingSpacerBottom__8a031:last-child {
border-width: medium;
border-style: none;
border-color: currentcolor;
border-image: none;
padding: 0px;
position: relative;
}

.container__8a031 > .bodyScrollingSpacerBottom__8a031:last-child::before {
background: linear-gradient (to top,var (--background-surface-high) 0,transparent 100%);
border-bottom-left-radius: var (--custom-border-radius);
bottom: 100%;
content: "";
display: block;
height: 60px;
left: 0px;
opacity: 0;
pointer-events: none;
position: absolute;
right: var (--space-16);
transition: opacity 0.2s ease-out;
z-index: 1;
}

.container__8a031 > .bodyScrollingSpacerBottom__8a031:last-child.showFade__8a031::before {
opacity: 1;
}

.container__8a031:has (> .bodyScrollingSpacerBottom__8a031:last-child) {
padding-bottom: 0px;
}

# 5 rule (s) matching 'modal_e44912'

.modal_e44912 {
--custom-viewport-padding: calc (var (--space-40) + var (--custom-app-top-bar-height));
background: var (--background-base-lower);
box-sizing: border-box;
container: modal-container / inline-size;
height: calc (100% - (var (--custom-viewport-padding))*2);
max-width: 1400px;
overflow: hidden;
width: calc (100% - (var (--custom-viewport-padding))*2);
}

/*  @ (max-width: 1600px) */
.modal_e44912 {
--custom-viewport-padding: var (--space-40);
}

/*  @outer-container (max-width: 1600px) */
.modal_e44912 {
--custom-viewport-padding: var (--space-40);
}

/*  @outer-container (max-width: 1080px) */
.modal_e44912 {
border-width: medium;
border-style: none;
border-color: currentcolor;
border-image: none;
border-radius: 0px;
height: 100%;
overflow-y: auto;
padding-top: var (--custom-app-top-bar-height);
width: 100%;
}

/*  @outer-container (max-width: 1080px) */
.is-mobile .modal_e44912 {
padding-top: 0px;
}

# 2 rule (s) matching 'modalContent_e44912'

/*  @outer-container (max-width: 1080px) */
.modalContent_e44912 {
border-top: 1px solid var (--border-muted);
}

.modalContent_e44912 {
box-sizing: border-box;
height: 100%;
width: 100%;
}

# 3 rule (s) matching 'sidebar__409aa'

.sidebar__409aa {
background-color: var (--background-base-lower);
border-inline-end: 1px solid var (--border-muted);
display: flex;
flex: 0 0 240px;
flex-direction: column;
padding-block: var (--space-16) 0;
padding-inline: var (--space-12) 0;
}

.sidebar__409aa.mobile__409aa {
flex: 1 1 auto;
}

.sidebar__409aa.mobile__409aa:not (.mobileNavigationOpen__409aa) {
display: none;
}

# 5 rule (s) matching 'item_caf372'

.item_caf372 {
align-items: center;
border-radius: var (--radius-sm);
color: var (--text-muted);
cursor: pointer;
display: flex;
gap: var (--space-sm);
justify-content: space-between;
padding: var (--space-xs);
}

.item_caf372.active_caf372, .item_caf372:hover {
background-color: var (--background-mod-muted);
color: var (--text-strong);
}

.item_caf372:hover:not (.active_caf372) {
transition: none;
}

.item_caf372.active_caf372 {
background-color: var (--background-mod-normal);
transition: background-color 0.3s;
}

.item_caf372.destructive_caf372:hover {
background-color: var (--background-feedback-critical);
}

# 1 rule (s) matching 'itemContent_caf372'

.itemContent_caf372 {
align-items: center;
display: flex;
gap: var (--space-xs);
}

# 1 rule (s) matching 'itemContainer_caf372'

.itemContainer_caf372 {
scroll-margin-block: var (--space-xs);
}

# 1 rule (s) matching 'sectionLabel__409aa'

.sectionLabel__409aa {
align-items: center;
display: flex;
flex-direction: row;
justify-content: space-between;
}

# 4 rule (s) matching 'sm_a22cb0'

.sm_a22cb0 .buttonChildrenWrapper_a22cb0 {
min-height: 30px;
min-width: 30px;
}

.sm_a22cb0.hasText_a22cb0 {
min-width: var (--__button-min-width,60px);
}

.sm_a22cb0.hasText_a22cb0 .buttonChildrenWrapper_a22cb0 {
padding: calc (var (--space-4) - 1px) calc (var (--space-12) - 1px);
}

.spinner-sm_a22cb0, .spinner-xs_a22cb0 {
min-height: 16px;
min-width: 16px;
transform: scale (0.75);
}

# 5 rule (s) matching 'md_a22cb0'

.md_a22cb0 .buttonChildrenWrapper_a22cb0 {
min-height: 38px;
min-width: 38px;
}

.md_a22cb0.hasText_a22cb0 {
min-width: var (--__button-min-width,100px);
}

.md_a22cb0.hasText_a22cb0 .buttonChildrenWrapper_a22cb0 {
padding: calc (var (--space-8) - 1px) calc (var (--space-16) - 1px);
}

.spinner-md_a22cb0 {
transform: scale (0.9);
}

.spinner-lg_a22cb0, .spinner-md_a22cb0 {
min-height: 20px;
min-width: 20px;
}

# 9 rule (s) matching 'primary_a22cb0'

.primary_a22cb0 {
background-color: var (--control-primary-background-default);
border-color: var (--control-primary-border-default);
color: var (--control-primary-text-default);
}

.primary_a22cb0:hover {
background-color: var (--control-primary-background-hover);
border-color: var (--control-primary-border-hover);
color: var (--control-primary-text-hover);
}

.primary_a22cb0:active {
background-color: var (--control-primary-background-active);
border-color: var (--control-primary-border-active);
color: var (--control-primary-text-active);
}

.critical-primary_a22cb0 {
background-color: var (--control-critical-primary-background-default);
border-color: var (--control-critical-primary-border-default);
color: var (--control-critical-primary-text-default);
}

.critical-primary_a22cb0:hover {
background-color: var (--control-critical-primary-background-hover);
border-color: var (--control-critical-primary-border-hover);
color: var (--control-critical-primary-text-hover);
}

.critical-primary_a22cb0:active {
background-color: var (--control-critical-primary-background-active);
border-color: var (--control-critical-primary-border-active);
color: var (--control-critical-primary-text-active);
}

.overlay-primary_a22cb0 {
background-color: var (--control-overlay-primary-background-default);
border-color: var (--control-overlay-primary-border-default);
color: var (--control-overlay-primary-text-default);
}

.overlay-primary_a22cb0:hover {
background-color: var (--control-overlay-primary-background-hover);
border-color: var (--control-overlay-primary-border-hover);
color: var (--control-overlay-primary-text-hover);
}

.overlay-primary_a22cb0:active {
background-color: var (--control-overlay-primary-background-active);
border-color: var (--control-overlay-primary-border-active);
color: var (--control-overlay-primary-text-active);
}

# 9 rule (s) matching 'secondary_a22cb0'

.secondary_a22cb0 {
background-color: var (--control-secondary-background-default);
border-color: var (--control-secondary-border-default);
color: var (--control-secondary-text-default);
}

.secondary_a22cb0:hover {
background-color: var (--control-secondary-background-hover);
border-color: var (--control-secondary-border-hover);
color: var (--control-secondary-text-hover);
}

.secondary_a22cb0:active {
background-color: var (--control-secondary-background-active);
border-color: var (--control-secondary-border-active);
color: var (--control-secondary-text-active);
}

.critical-secondary_a22cb0 {
background-color: var (--control-critical-secondary-background-default);
border-color: var (--control-critical-secondary-border-default);
color: var (--control-critical-secondary-text-default);
}

.critical-secondary_a22cb0:hover {
background-color: var (--control-critical-secondary-background-hover);
border-color: var (--control-critical-secondary-border-hover);
color: var (--control-critical-secondary-text-hover);
}

.critical-secondary_a22cb0:active {
background-color: var (--control-critical-secondary-background-active);
border-color: var (--control-critical-secondary-border-active);
color: var (--control-critical-secondary-text-active);
}

.overlay-secondary_a22cb0 {
background-color: var (--control-overlay-secondary-background-default);
border-color: var (--control-overlay-secondary-border-default);
color: var (--control-overlay-secondary-text-default);
}

.overlay-secondary_a22cb0:hover {
background-color: var (--control-overlay-secondary-background-hover);
border-color: var (--control-overlay-secondary-border-hover);
color: var (--control-overlay-secondary-text-hover);
}

.overlay-secondary_a22cb0:active {
background-color: var (--control-overlay-secondary-background-active);
border-color: var (--control-overlay-secondary-border-active);
color: var (--control-overlay-secondary-text-active);
}

no rules match 'danger_a22cb0'

# 7 rule (s) matching 'hasText_a22cb0'

.xs_a22cb0.hasText_a22cb0 {
min-width: var (--__button-min-width,60px);
}

.xs_a22cb0.hasText_a22cb0 .buttonChildrenWrapper_a22cb0 {
padding: calc (var (--space-4) - 1px) calc (var (--space-8) - 1px);
}

.sm_a22cb0.hasText_a22cb0 {
min-width: var (--__button-min-width,60px);
}

.sm_a22cb0.hasText_a22cb0 .buttonChildrenWrapper_a22cb0 {
padding: calc (var (--space-4) - 1px) calc (var (--space-12) - 1px);
}

.md_a22cb0.hasText_a22cb0 {
min-width: var (--__button-min-width,100px);
}

.md_a22cb0.hasText_a22cb0 .buttonChildrenWrapper_a22cb0 {
padding: calc (var (--space-8) - 1px) calc (var (--space-16) - 1px);
}

.fullWidth_a22cb0.hasText_a22cb0 {
flex: 1 1 0%;
width: 100%;
}

# 1 rule (s) matching 'fullWidth_a22cb0'

.fullWidth_a22cb0.hasText_a22cb0 {
flex: 1 1 0%;
width: 100%;
}

# 1 rule (s) matching 'container_e45859'

.container_e45859 {
border-radius: 8px;
box-sizing: border-box;
display: block;
padding: 1px 0px;
transition: none;
}

# 3 rule (s) matching 'title_f75fb0'

.title_f75fb0 {
flex: 0 0 auto;
padding-inline-end: var (--space-8);
padding-top: var (--space-8);
position: relative;
z-index: 100;
}

.stop-animations .title_f75fb0 {
app-region: no-drag;
}

.theme-light.custom-client-theme .title_f75fb0 {
--background-gradient-lowest: var (--background-gradient-chat);
}

# 1 rule (s) matching 'upperContainer__9293f'

.upperContainer__9293f {
display: flex;
flex: 1 1 0%;
}

# 3 rule (s) matching 'form_f75fb0'

.form_f75fb0 {
flex-shrink: 0;
margin-top: -16px;
position: relative;
}

.form_f75fb0, .formWithLoadedChatInput_f75fb0 {
padding-inline: var (--space-xs);
}

.refresh-fast-follow-avatars .form_f75fb0 {
padding-inline: min (var (--space-xs),var (--space-8));
}

# 8 rule (s) matching 'channelTextArea__74017'

.channelTextArea__74017 {
border: 1px solid var (--border-muted);
border-radius: var (--radius-sm);
position: relative;
text-indent: 0px;
transition: border-color 0.2s;
width: 100%;
}

.channelTextArea__74017:not (.highlighted__74017) {
transition: box-shadow 0.2s;
}

.channelTextArea__74017.error__74017:not (:hover):not (:focus-within) {
box-shadow: 0 0 0 2px var (--text-feedback-critical);
}

.channelTextArea__74017.highlighted__74017 {
border: 1px solid var (--border-muted);
box-shadow: 0 0 0 4px var (--blue-345);
}

.channelTextArea__74017:focus-within {
border-color: var (--border-subtle);
box-shadow: none;
}

:where (.custom-theme-background) .channelTextArea__74017 {
background: var (--background-gradient-highest,var (--input-background-default));
border-color: var (--app-frame-border);
}

.refresh-fast-follow-distinct-borders .channelTextArea__74017 {
border: 1px solid var (--border-subtle);
}

.refresh-fast-follow-distinct-borders .channelTextArea__74017:focus-within {
border-color: var (--app-frame-border);
}

# 17 rule (s) matching 'scrollableContainer__74017'

.scrollableContainer__74017 {
backface-visibility: hidden;
border-radius: 8px;
max-height: var (--custom-channel-textarea-text-area-max-height);
overflow: hidden scroll;
}

.no-webkit-scrollbar .scrollableContainer__74017 {
scrollbar-width: none;
}

.background-opacity-low .scrollableContainer__74017 {
background-color: hsl (var (--primary-500-hsl)/.5);
}

.background-opacity-low .scrollableContainer__74017 .textArea__74017 {
color: var (--white);
}

.background-opacity-medium .scrollableContainer__74017 {
background-color: hsl (var (--primary-500-hsl)/.7);
}

.background-opacity-medium .scrollableContainer__74017 .textArea__74017 {
color: var (--white);
}

.background-opacity-high .scrollableContainer__74017 {
background-color: hsl (var (--primary-500-hsl)/.9);
}

.scrollableContainer__74017::-webkit-scrollbar {
height: 12px;
width: 12px;
}

.scrollableContainer__74017::-webkit-scrollbar-thumb, .scrollableContainer__74017::-webkit-scrollbar-track {
background-clip: padding-box;
border: 4px solid transparent;
}

.scrollableContainer__74017::-webkit-scrollbar-track {
border-width: initial;
}

.scrollableContainer__74017::-webkit-scrollbar-thumb {
background-color: hsl (var (--primary-800-hsl)/.6);
border-radius: 8px;
}

.channelTextAreaDisabled__74017 .scrollableContainer__74017 {
opacity: 1;
}

.enable-forced-colors .scrollableContainer__74017 {
overflow-y: auto;
padding-inline-end: 12px;
}

.enable-forced-colors .scrollableContainer__74017::-webkit-scrollbar {
width: 8px;
}

.enable-forced-colors .scrollableContainer__74017::-webkit-scrollbar-track {
border-radius: 0px 8px 8px 0px;
}

.enable-forced-colors .scrollableContainer__74017::-webkit-scrollbar-thumb, .enable-forced-colors
.scrollableContainer__74017::-webkit-scrollbar-track {
border-width: 1px;
}

.enable-forced-colors .scrollableContainer__74017::-webkit-scrollbar-thumb {
background-color: canvastext;
}

# 5 rule (s) matching 'inner__74017'

.flushContainer__74017 .inner__74017 {
padding-inline-start: 0px;
}

.inner__74017 {
display: flex;
padding-inline-start: calc (var (--space-16) - 1px);
position: relative;
}

.charCountShowing__74017 .inner__74017 {
min-height: calc (var (--custom-channel-textarea-text-area-height) + var (--space-32));
}

.charCountUpsellShowing__74017 .inner__74017 {
min-height: calc (var (--custom-channel-textarea-text-area-height)*2);
}

.theme-brand .inner__74017 {
background-color: var (--brand-400);
}

# 5 rule (s) matching 'panels__5e434'

.panels__5e434 {
flex: 0 0 auto;
z-index: 10;
--custom-panels-spacing: var (--space-xs);
background: var (--background-gradient-highest,var (--background-base-low));
border: 1px solid var (--border-muted);
border-radius: var (--radius-sm);
bottom: var (--custom-panels-spacing);
box-sizing: border-box;
inset-inline-start: var (--custom-panels-spacing);
justify-content: space-between;
position: absolute;
width: calc (100% - var (--custom-panels-spacing)*2);
}

.panels__5e434.disablePointersWhileSorting__5e434 {
pointer-events: none;
}

.refresh-fast-follow-avatars .panels__5e434 {
--custom-panels-spacing: min (var (--space-xs),var (--space-8));
}

.custom-theme-background .panels__5e434 {
border-color: var (--app-frame-border);
}

.enable-forced-colors .panels__5e434 {
border-top: 2px solid canvastext;
}

# 5 rule (s) matching 'container__37e49'

.container__37e49 {
align-items: center;
background: unset;
border-radius: 7px;
box-sizing: border-box;
container-type: inline-size;
display: flex;
flex-shrink: 0;
font-size: 14px;
font-weight: var (--font-weight-medium);
gap: var (--space-xs);
height: 56px;
justify-content: space-between;
margin-inline-start: -1px;
min-height: var (--custom-rtc-account-height);
padding: calc (var (--custom-guild-list-padding) - var (--custom-panels-spacing) + 4px);
position: relative;
z-index: 1;
}

.container__37e49 .avatar__37e49 {
cursor: pointer;
flex-shrink: 0;
}

.container__37e49 .avatar__37e49:hover {
opacity: 0.8;
}

.container__37e49 .redIcon__37e49 {
color: var (--icon-feedback-critical);
}

.refresh-fast-follow-avatars .container__37e49 {
padding: min (var (--space-xs),var (--space-8));
}

# 1 rule (s) matching 'scrollerBase_d125d2'

.scrollerBase_d125d2 {
box-sizing: border-box;
flex: 1 1 auto;
min-height: 0px;
position: relative;
}

# 7 rule (s) matching 'thin_d125d2'

.auto_d125d2, .none_d125d2, .thin_d125d2 {
}

.thin_d125d2::-webkit-scrollbar {
height: 8px;
width: 8px;
}

.thin_d125d2::-webkit-scrollbar-track {
background-color: var (--scrollbar-thin-track);
border-top-style:;
border-top-width:;
border-right-style:;
border-right-width:;
border-bottom-style:;
border-bottom-width:;
border-left-style:;
border-left-width:;
border-image-source:;
border-image-slice:;
border-image-width:;
border-image-outset:;
border-image-repeat:;
border-color: var (--scrollbar-thin-track);
}

.thin_d125d2::-webkit-scrollbar-thumb {
background-clip: padding-box;
background-color: var (--scrollbar-thin-thumb);
border: 2px solid transparent;
border-radius: 4px;
min-height: 40px;
}

.thin_d125d2::-webkit-scrollbar-corner {
background-color: transparent;
}

.no-webkit-scrollbar .thin_d125d2 {
scrollbar-color: var (--scrollbar-thin-thumb) var (--scrollbar-thin-track);
scrollbar-width: thin;
}

.no-webkit-scrollbar .thin_d125d2.fade_d125d2.scrolling_d125d2, .no-webkit-scrollbar .thin_d125d2.fade_d125d2:hover {
scrollbar-color: var (--scrollbar-thin-thumb) var (--scrollbar-thin-track);
}

## Popouts: tooltip

# 5 rule (s) matching 'tooltip_c36707'

.tooltip_c36707 {
box-sizing: border-box;
font-size: 14px;
font-weight: var (--font-weight-medium);
line-height: 16px;
max-width: 190px;
position: relative;
overflow-wrap: break-word;
border: 1px solid var (--border-subtle);
border-radius: 8px;
will-change: opacity, transform;
z-index: 1002;
}

.enable-forced-colors .tooltip_c36707 {
border: 1px solid canvastext;
}

.tooltip_c36707 {
box-shadow: var (--shadow-high);
color: var (--text-default);
}

.custom-theme-background .tooltip_c36707 {
border: 1px solid var (--border-strong);
}

.refresh-fast-follow-distinct-borders .tooltip_c36707 {
border-color: var (--app-frame-border);
}

## Popouts: menu

# 3 rule (s) matching 'menuItem_ce8328'

.menuItem_ce8328 {
display: flex;
flex-direction: column;
}

.menuItem_ce8328::after {
background-color: var (--user-profile-border);
content: "";
display: block;
height: 1px;
margin: 8px 4px;
}

.menuItem_ce8328:last-child::after {
display: none;
}

# 3 rule (s) matching 'menuItemContent_ce8328'

.menuItemContent_ce8328 {
align-items: center;
display: flex;
flex-grow: 1;
gap: 8px;
justify-content: space-between;
padding-bottom: var (--space-8);
padding-inline-start: var (--space-8);
padding-top: var (--space-8);
}

.menuItemContent_ce8328:last-child {
padding-inline-end: var (--space-8);
}

.menuItemContent_ce8328 .submenuIconWrapper_ce8328 {
padding-inline-end: 0px;
}

## Switch

# 3 rule (s) matching 'switchItem_c1e9c4'

.switchItem_c1e9c4 {
align-items: center;
display: flex;
gap: var (--space-16);
justify-content: space-between;
min-height: var (--space-32);
padding: var (--space-8);
}

.switchItem_c1e9c4 .labelContainer_c1e9c4 {
min-height: 0px;
padding: 0px;
}

.switchItem_c1e9c4 .label_c1e9c4 {
white-space: normal;
}

