# Preview Instructions

## How to Preview the Landing Page

### Option 1: Direct File Open
1. Navigate to the `landing_page` folder
2. Double-click `index.html`
3. It will open in your default browser

### Option 2: Using Python HTTP Server
```bash
cd landing_page
python3 -m http.server 8000
```
Then open: http://localhost:8000

### Option 3: Using Node.js http-server
```bash
cd landing_page
npx http-server -p 8000
```
Then open: http://localhost:8000

### Option 4: Using VS Code Live Server
1. Open the `landing_page` folder in VS Code
2. Install "Live Server" extension if not already installed
3. Right-click `index.html` and select "Open with Live Server"

## What to Test

### Desktop Interactions
- ✅ Click the **Start Button** - Start Menu should appear
- ✅ **Right-click** on desktop - Context menu appears
- ✅ **Double-click** desktop icons - Window opens with taskbar button
- ✅ Drag window title bars - Windows should move

### Taskbar
- ✅ Click **system tray toggle** (◀) - System tray collapses/expands
- ✅ Click **date** - Shows full date in notification
- ✅ Click **volume icon** - Shows volume notification
- ✅ Verify **clock** updates every minute

### Start Menu Items
- ✅ Click any Start Menu item - Shows notification
- ✅ Click "Turn Off Computer" - Shows shutdown screen
- ✅ Click outside Start Menu - Menu closes

### Responsive Design
- ✅ Resize browser window to mobile width (< 480px)
- ✅ Verify taskbar adapts to smaller screen
- ✅ Verify Start Menu width adjusts
- ✅ Check touch targets are appropriately sized

### Keyboard Shortcuts
- ✅ Press `F5` - Refresh notification appears
- ✅ Press `Ctrl + Alt + Del` - Task Manager notification

### Notifications
- ✅ Notifications appear in bottom-right
- ✅ Close button (×) works
- ✅ Auto-dismiss after 4 seconds

## Expected Behavior

### Sounds
Note: Sounds may not play on first load due to browser autoplay restrictions. User interaction is typically required first.

### Performance
- Smooth animations
- Responsive clicks
- No lag when dragging windows

## Browser Testing Checklist

- [ ] Chrome/Edge
- [ ] Firefox
- [ ] Safari
- [ ] Mobile Safari (iOS)
- [ ] Chrome Mobile (Android)

## Common Issues

### Sounds Not Playing
- **Cause**: Browser autoplay policy
- **Solution**: Click anywhere first, then sounds will work

### Images Not Loading
- **Cause**: Incorrect file paths
- **Solution**: Verify all images are in `src/img/` folder

### Layout Broken on Mobile
- **Cause**: Viewport not set
- **Solution**: Already handled in HTML `<meta name="viewport">`

## Mobile Testing

### iOS Safari
- Open on iPhone/iPad
- Test touch interactions
- Verify responsive layout
- Check gesture bar at bottom

### Android Chrome
- Test on various screen sizes
- Verify taskbar responsiveness
- Check system tray collapse

## Screenshots Locations to Check

1. **Desktop** - Full layout with wallpaper
2. **Start Menu Open** - Blue menu with items
3. **Window Open** - Floating window with taskbar button
4. **Notification** - Bottom-right bubble
5. **Mobile View** - Compact taskbar
6. **Context Menu** - Right-click menu

## Performance Metrics

Expected load times:
- Initial load: < 500ms
- Image loading: < 1s
- Interaction response: < 50ms

## Accessibility

- Keyboard navigation works for most elements
- Click targets are adequately sized
- Text is readable with good contrast

---

Enjoy exploring your Windows XP Launcher landing page! 🪟
