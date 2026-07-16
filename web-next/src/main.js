// Same stylesheets as the legacy app, same load order — pixel parity is the
// contract (no visual redesign in the rewrite).
import './styles/styles.css';
import './styles/app.css';
import './styles/blocks.css';
import './styles/companion.css';
import './styles/themes.css';
import './styles/beam.css';

import { mount } from 'svelte';
import App from './App.svelte';

const app = mount(App, { target: document.getElementById('app-root') });

export default app;
