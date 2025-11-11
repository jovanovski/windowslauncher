// Windows XP Launcher - Main JavaScript

class WindowsXPLauncher {
    constructor() {
        this.startMenuVisible = false;
        this.systemTrayCollapsed = false;
        this.contextMenuVisible = false;
        this.openWindows = [];

        this.init();
    }

    init() {
        this.setupEventListeners();
        this.updateClock();
        this.updateDate();
        this.loadDesktopIcons();

        // Update clock every second
        setInterval(() => this.updateClock(), 1000);

        // Play startup sound if available
        this.playSound('startup');

        console.log('Windows XP Launcher initialized');
    }

    setupEventListeners() {
        // Start button click
        const startButton = document.getElementById('start-button');
        startButton.addEventListener('click', (e) => {
            e.stopPropagation();
            this.toggleStartMenu();
            this.playSound('click');
        });

        // Close start menu when clicking outside
        document.addEventListener('click', (e) => {
            const startMenu = document.getElementById('start-menu');
            const startButton = document.getElementById('start-button');

            if (this.startMenuVisible &&
                !startMenu.contains(e.target) &&
                !startButton.contains(e.target)) {
                this.hideStartMenu();
            }

            // Close context menu
            if (this.contextMenuVisible) {
                this.hideContextMenu();
            }
        });

        // Right-click context menu
        const mainBackground = document.getElementById('main-background');
        mainBackground.addEventListener('contextmenu', (e) => {
            // Only show context menu if clicking on desktop (not on icons or windows)
            if (e.target === mainBackground || e.target.classList.contains('desktop-icons-container')) {
                e.preventDefault();
                this.showContextMenu(e.clientX, e.clientY);
            }
        });

        // Date click - show calendar notification
        const dateContainer = document.getElementById('date-container');
        dateContainer.addEventListener('click', (e) => {
            e.stopPropagation();
            this.showNotification('Calendar', this.getFullDate());
            this.playSound('click');
        });

        // Volume icon click
        const volumeIconWrapper = document.getElementById('volume-icon-wrapper');
        volumeIconWrapper.addEventListener('click', (e) => {
            e.stopPropagation();
            this.showNotification('Volume', 'Volume control would appear here');
            this.playSound('click');
        });

        // Close notification button
        const closeNotificationButton = document.getElementById('close-notification-button');
        closeNotificationButton.addEventListener('click', () => {
            this.hideNotification();
        });

        // Start menu items
        const startMenuItems = document.querySelectorAll('.start-menu-item');
        startMenuItems.forEach(item => {
            item.addEventListener('click', (e) => {
                const textEl = item.querySelector('.item-text');
                if (textEl) {
                    const text = textEl.textContent;
                    this.handleStartMenuItem(text);
                    this.playSound('click');
                }
            });
        });

        // All Programs button
        const allPrograms = document.getElementById('all-programs');
        if (allPrograms) {
            allPrograms.addEventListener('click', () => {
                this.showNotification('All Programs', 'All Programs menu would appear here');
                this.playSound('click');
            });
        }

        // Shutdown button
        const shutdownButton = document.getElementById('shutdown-button');
        if (shutdownButton) {
            shutdownButton.addEventListener('click', () => {
                this.hideStartMenu();
                this.showShutdownScreen();
                this.playSound('click');
            });
        }

        // Search box
        const searchBox = document.getElementById('search-box');
        if (searchBox) {
            searchBox.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    const query = searchBox.value;
                    if (query) {
                        this.showNotification('Run', `Running: ${query}`);
                        searchBox.value = '';
                        this.hideStartMenu();
                        this.playSound('click');
                    }
                }
            });
        }

        // Window resize handler
        window.addEventListener('resize', () => {
            this.handleResize();
        });
    }

    toggleStartMenu() {
        const startMenu = document.getElementById('start-menu');

        if (this.startMenuVisible) {
            this.hideStartMenu();
        } else {
            startMenu.classList.add('visible');
            this.startMenuVisible = true;
        }
    }

    hideStartMenu() {
        const startMenu = document.getElementById('start-menu');
        startMenu.classList.remove('visible');
        this.startMenuVisible = false;
    }


    showContextMenu(x, y) {
        const contextMenu = document.getElementById('context-menu');
        contextMenu.style.display = 'block';
        contextMenu.style.left = `${x}px`;
        contextMenu.style.top = `${y}px`;
        this.contextMenuVisible = true;

        // Setup context menu items
        const items = contextMenu.querySelectorAll('.context-menu-item');
        items.forEach(item => {
            item.onclick = () => {
                this.handleContextMenuItem(item.textContent);
                this.hideContextMenu();
            };
        });
    }

    hideContextMenu() {
        const contextMenu = document.getElementById('context-menu');
        contextMenu.style.display = 'none';
        this.contextMenuVisible = false;
    }

    handleContextMenuItem(itemText) {
        console.log('Context menu item clicked:', itemText);

        if (itemText === 'Refresh') {
            this.showNotification('Refresh', 'Desktop refreshed');
            this.playSound('click');
        } else if (itemText === 'Properties') {
            this.showNotification('Properties', 'Display properties would appear here');
            this.playSound('click');
        }
    }

    handleStartMenuItem(itemText) {
        console.log('Start menu item clicked:', itemText);

        // Hide start menu
        this.hideStartMenu();

        // Handle different menu items
        if (itemText === 'Windows Update') {
            this.showNotification('Windows Update', 'Checking for updates...');
        } else {
            this.showNotification(itemText, `Opening ${itemText}...`);
        }
    }

    showNotification(title, text) {
        const bubble = document.getElementById('notification-bubble');
        const titleEl = document.getElementById('notification-title');
        const textEl = document.getElementById('notification-text');

        titleEl.textContent = title;
        textEl.textContent = text;
        bubble.style.display = 'block';

        // Auto-hide after 4 seconds
        setTimeout(() => {
            this.hideNotification();
        }, 4000);
    }

    hideNotification() {
        const bubble = document.getElementById('notification-bubble');
        bubble.style.display = 'none';
    }

    showShutdownScreen() {
        const splash = document.getElementById('safe-to-turn-off-splash');
        splash.style.display = 'flex';

        // Hide after 3 seconds (demo purposes)
        setTimeout(() => {
            splash.style.display = 'none';
        }, 3000);
    }

    updateClock() {
        const now = new Date();
        const hours = now.getHours().toString().padStart(2, '0');
        const minutes = now.getMinutes().toString().padStart(2, '0');

        const clockTime = document.getElementById('clock-time');
        clockTime.textContent = `${hours}:${minutes}`;
    }

    updateDate() {
        const now = new Date();
        const day = now.getDate();
        const ordinal = this.getOrdinalSuffix(day);

        const dayEl = document.getElementById('date-day');
        const ordinalEl = document.getElementById('date-ordinal');

        dayEl.textContent = day;
        ordinalEl.textContent = ordinal;
    }

    getOrdinalSuffix(day) {
        if (day > 3 && day < 21) return 'th';
        switch (day % 10) {
            case 1: return 'st';
            case 2: return 'nd';
            case 3: return 'rd';
            default: return 'th';
        }
    }

    getFullDate() {
        const now = new Date();
        const options = { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' };
        return now.toLocaleDateString('en-US', options);
    }

    loadDesktopIcons() {
        const container = document.getElementById('desktop-icons-container');

        const icons = [
            { name: 'My Computer', icon: '💻' },
            { name: 'My Documents', icon: '📁' },
            { name: 'Recycle Bin', icon: '🗑️' },
            { name: 'Internet Explorer', icon: '🌐' },
        ];

        icons.forEach(icon => {
            const iconEl = this.createDesktopIcon(icon.name, icon.icon);
            container.appendChild(iconEl);
        });

        // Also populate the apps list in start menu
        this.loadStartMenuApps();
    }

    loadStartMenuApps() {
        const appsList = document.getElementById('apps-list');

        const apps = [
            { name: 'Internet Explorer', icon: '🌐' },
            { name: 'E-mail', icon: '📧' },
            { name: 'Windows Media Player', icon: '🎵' },
            { name: 'Paint', icon: '🎨' },
            { name: 'Notepad', icon: '📝' },
        ];

        apps.forEach(app => {
            const appEl = document.createElement('div');
            appEl.className = 'app-item';

            const iconSpan = document.createElement('span');
            iconSpan.className = 'item-icon';
            iconSpan.textContent = app.icon;

            const textSpan = document.createElement('span');
            textSpan.className = 'item-text';
            textSpan.textContent = app.name;

            appEl.appendChild(iconSpan);
            appEl.appendChild(textSpan);

            appEl.addEventListener('click', () => {
                this.openWindow(app.name);
                this.hideStartMenu();
                this.playSound('click');
            });

            appsList.appendChild(appEl);
        });
    }

    createDesktopIcon(name, icon) {
        const div = document.createElement('div');
        div.className = 'desktop-icon';

        const iconText = document.createElement('div');
        iconText.style.fontSize = '48px';
        iconText.textContent = icon;

        const label = document.createElement('span');
        label.textContent = name;

        div.appendChild(iconText);
        div.appendChild(label);

        // Double-click to open
        div.addEventListener('dblclick', () => {
            this.openWindow(name);
            this.playSound('click');
        });

        return div;
    }

    openWindow(title) {
        console.log('Opening window:', title);
        this.showNotification(title, `Opening ${title}...`);

        // Create a simple floating window (simplified version)
        const container = document.getElementById('floating-windows-container');
        const windowEl = document.createElement('div');
        windowEl.className = 'floating-window';
        windowEl.style.left = '100px';
        windowEl.style.top = '100px';
        windowEl.style.width = '400px';
        windowEl.style.height = '300px';

        const titleBar = document.createElement('div');
        titleBar.style.cssText = 'background: linear-gradient(to bottom, #0054E3 0%, #0054E3 100%); color: white; padding: 5px 10px; font-size: 12px; font-weight: bold; cursor: move;';
        titleBar.textContent = title;

        const content = document.createElement('div');
        content.style.cssText = 'padding: 20px; height: calc(100% - 30px); overflow: auto;';
        content.textContent = `This is ${title}`;

        windowEl.appendChild(titleBar);
        windowEl.appendChild(content);
        container.appendChild(windowEl);

        // Add to taskbar
        this.addTaskbarButton(title, windowEl);

        // Make window draggable
        this.makeWindowDraggable(windowEl, titleBar);

        this.openWindows.push({ title, element: windowEl });
    }

    makeWindowDraggable(windowEl, titleBar) {
        let pos1 = 0, pos2 = 0, pos3 = 0, pos4 = 0;

        titleBar.onmousedown = dragMouseDown;

        function dragMouseDown(e) {
            e.preventDefault();
            pos3 = e.clientX;
            pos4 = e.clientY;
            document.onmouseup = closeDragElement;
            document.onmousemove = elementDrag;
        }

        function elementDrag(e) {
            e.preventDefault();
            pos1 = pos3 - e.clientX;
            pos2 = pos4 - e.clientY;
            pos3 = e.clientX;
            pos4 = e.clientY;
            windowEl.style.top = (windowEl.offsetTop - pos2) + 'px';
            windowEl.style.left = (windowEl.offsetLeft - pos1) + 'px';
        }

        function closeDragElement() {
            document.onmouseup = null;
            document.onmousemove = null;
        }
    }

    addTaskbarButton(title, windowEl) {
        const taskbarSpace = document.getElementById('taskbar-empty-space');
        const button = document.createElement('div');
        button.className = 'taskbar-button';
        button.textContent = title;

        button.addEventListener('click', () => {
            // Toggle window visibility
            if (windowEl.style.display === 'none') {
                windowEl.style.display = 'block';
                button.classList.add('active');
            } else {
                windowEl.style.display = 'none';
                button.classList.remove('active');
            }
            this.playSound('click');
        });

        taskbarSpace.appendChild(button);
    }

    playSound(soundName) {
        try {
            const audio = new Audio(`src/sounds/${soundName}.mp3`);
            audio.volume = 0.3;
            audio.play().catch(err => {
                // Silently fail if sound can't play (browser restrictions)
                console.log('Sound playback prevented:', err.message);
            });
        } catch (err) {
            console.log('Sound not available:', soundName);
        }
    }

    handleResize() {
        // Adjust any responsive elements if needed
        console.log('Window resized');
    }
}

// Initialize the launcher when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
    window.launcher = new WindowsXPLauncher();
});

// Add some fun easter eggs
document.addEventListener('keydown', (e) => {
    // Ctrl + Alt + Del
    if (e.ctrlKey && e.altKey && e.key === 'Delete') {
        e.preventDefault();
        const launcher = window.launcher;
        if (launcher) {
            launcher.showNotification('Task Manager', 'Windows Task Manager would appear here');
        }
    }

    // F5 for refresh
    if (e.key === 'F5') {
        e.preventDefault();
        const launcher = window.launcher;
        if (launcher) {
            launcher.showNotification('Refresh', 'Desktop refreshed');
        }
    }
});
