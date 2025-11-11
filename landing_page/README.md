# Windows XP Launcher - Landing Page

A responsive web implementation of the Windows XP Launcher interface, recreating the classic Windows XP desktop experience in HTML, CSS, and JavaScript.

## Features

### Desktop Elements
- **Background Wallpaper**: Classic Windows XP Bliss wallpaper
- **Desktop Icons**: Draggable icons for My Computer, My Documents, Recycle Bin, and Internet Explorer
- **Floating Windows**: Draggable windows with title bars
- **Context Menu**: Right-click desktop for context menu options

### Taskbar
- **Start Button**: Click to open the Start Menu with classic XP styling
- **Open Window Buttons**: Shows currently open windows
- **System Tray**:
  - Weather temperature display
  - Date with ordinal suffix (26th)
  - Volume control icon
  - Live clock (HH:MM format)
  - Collapsible system tray toggle

### Start Menu
- Classic Windows XP blue gradient design
- Common shortcuts: My Documents, My Pictures, My Music, My Computer
- Control Panel, Search, Help and Support, Run
- Log Off and Turn Off Computer options

### Interactive Features
- **Notifications**: System notification bubbles (bottom-right)
- **Sound Effects**: Click sounds and startup sound
- **Shutdown Screen**: "It's now safe to turn off your computer" screen
- **Keyboard Shortcuts**:
  - `Ctrl + Alt + Del`: Task Manager notification
  - `F5`: Refresh desktop notification

## Responsive Design

The landing page is fully responsive and adapts to different screen sizes:

### Desktop (> 768px)
- Full taskbar with all system tray elements visible
- Wide start menu
- Large desktop icons

### Tablet (768px - 480px)
- Adjusted taskbar spacing
- Hidden weather temperature
- Compact system tray
- Responsive start menu width

### Mobile (< 480px)
- Compact start button
- Minimal system tray
- Optimized touch targets
- Gesture bar for mobile navigation
- Adjusted floating window margins

### Orientation Support
- Landscape mode optimizations for devices with limited height
- Scrollable start menu on small screens

### Touch Devices
- Larger touch targets (min 36px height)
- Touch-friendly interactive elements

## File Structure

```
landing_page/
├── index.html              # Main HTML file
├── README.md              # This file
└── src/
    ├── css/
    │   └── main.css       # All styling and responsive rules
    ├── js/
    │   └── main.js        # Interactive functionality
    ├── img/               # Image assets
    │   ├── cursor.png
    │   ├── sound.png
    │   ├── start.png
    │   ├── taskbar.png
    │   └── wallpaper1.jpg
    └── sounds/            # Audio files
        ├── click.mp3
        └── startup.mp3
```

## Usage

### Local Development
1. Open `index.html` in a modern web browser
2. No build process or server required - it's pure HTML/CSS/JS

### Deploy to Web Server
1. Upload the entire `landing_page` directory to your web server
2. Ensure all file paths remain relative
3. Access via `http://yourdomain.com/landing_page/`

## Browser Compatibility

- Chrome 90+
- Firefox 88+
- Safari 14+
- Edge 90+
- Mobile browsers (iOS Safari, Chrome Mobile)

## Customization

### Changing the Wallpaper
Replace `src/img/wallpaper1.jpg` with your desired image, or update the CSS:

```css
.main-background {
    background-image: url('../img/your-wallpaper.jpg');
}
```

### Adding Desktop Icons
Modify the `loadDesktopIcons()` function in `src/js/main.js`:

```javascript
const icons = [
    { name: 'Your App', icon: '🎯' },
    // Add more icons here
];
```

### Customizing Colors
Edit the CSS variables in `src/css/main.css`:
- Taskbar gradient: `.taskbar-background`
- Start menu colors: `.start-menu`
- System tray: `.system-tray`

### Adding Sounds
Place MP3 files in `src/sounds/` and call them with:

```javascript
this.playSound('your-sound-name');
```

## Interactive Elements

### Click Interactions
- **Start Button**: Opens/closes Start Menu
- **Desktop Icons**: Double-click to open windows
- **Taskbar Buttons**: Toggle window visibility
- **System Tray Icons**: Show notifications
- **Date**: Shows full date in notification
- **Volume**: Shows volume notification

### Right-Click
- **Desktop**: Shows context menu with Refresh and Properties

### Windows
- **Draggable**: Click and drag title bar to move windows
- **Taskbar Integration**: Opens taskbar button when window is created

## Performance

- Optimized CSS with hardware acceleration
- Efficient JavaScript event handling
- Lazy loading of sounds
- Minimal dependencies (vanilla JavaScript)

## Known Limitations

- Browser autoplay policies may prevent startup sound on some browsers
- Custom cursor may not work on all mobile devices
- Some effects simplified for web compatibility

## Credits

Based on the Android Windows XP Launcher application layout files:
- `activity_main.xml`
- `taskbar_xp.xml`

## License

This is a demonstration project recreating the Windows XP interface for educational purposes.
