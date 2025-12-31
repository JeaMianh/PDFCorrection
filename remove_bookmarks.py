import sys
import os
from PyPDF2 import PdfReader, PdfWriter

def remove_bookmarks(input_path):
    if not os.path.exists(input_path):
        print(f"Error: File '{input_path}' not found.")
        return

    try:
        reader = PdfReader(input_path)
        writer = PdfWriter()

        # Copy all pages to the writer
        # By default, PdfWriter does not copy the outline (bookmarks) unless specifically instructed
        for page in reader.pages:
            writer.add_page(page)

        # Construct output filename
        base, ext = os.path.splitext(input_path)
        output_path = f"{base}_cleaned{ext}"

        # Write the new PDF
        with open(output_path, "wb") as f:
            writer.write(f)

        print(f"Success! Created new PDF without bookmarks: {output_path}")

    except Exception as e:
        print(f"An error occurred: {e}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python remove_bookmarks.py <path_to_pdf>")
        print("Example: python remove_bookmarks.py my_book.pdf")
    else:
        input_file = sys.argv[1]
        remove_bookmarks(input_file)
