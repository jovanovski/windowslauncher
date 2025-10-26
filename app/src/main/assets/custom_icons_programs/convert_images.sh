#!/bin/bash

# Script to crop PNG images to 200x200, convert to WebP, and remove originals
echo "Starting image processing..."

# Counter for processed images
processed=0
failed=0
counter=1

# Find all PNG files (case insensitive) and process them in sorted order
find . -maxdepth 1 -iname "*.png" -type f | sort | while read -r png_file; do
    # Output WebP file name with sequential numbering
    webp_file="program_${counter}.webp"
    
    echo "Processing ($counter): $png_file -> $webp_file"
    
    # Use ImageMagick to crop to 200x200 (center crop) and convert to WebP
    # -gravity center ensures the crop is centered from the middle
    # -crop 200x200+0+0 crops exactly 200x200 pixels from the center
    # +repage removes any canvas/page information
    if magick "$png_file" -gravity center -crop 200x200+0+0 +repage "$webp_file"; then
        # If conversion successful, remove the original PNG
        if rm "$png_file"; then
            echo "✅ Successfully processed and removed: $png_file"
            ((processed++))
        else
            echo "⚠️  Converted but failed to remove: $png_file"
            ((failed++))
        fi
    else
        echo "❌ Failed to process: $png_file"
        ((failed++))
    fi
    
    ((counter++))
done

echo ""
echo "Image processing complete!"
echo "Successfully processed: $processed images"
echo "Failed: $failed images"