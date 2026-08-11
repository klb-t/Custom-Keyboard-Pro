import json
import sys
import glob

def validate_profile(file_path):
    with open(file_path, 'r') as f:
        data = json.load(f)
    
    if 'schemaVersion' not in data:
        raise ValueError("Missing schemaVersion")
    if 'profileId' not in data:
        raise ValueError("Missing profileId")
    if 'abstractionId' not in data:
        raise ValueError("Missing abstractionId")
        
    print(f"Validated {file_path} (Profile: {data['profileId']})")

if __name__ == '__main__':
    files = glob.glob('profiles/*.json')
    failed = False
    for f in files:
        try:
            validate_profile(f)
        except Exception as e:
            print(f"Error in {f}: {e}")
            failed = True
    if failed:
        sys.exit(1)
    else:
        print("All profiles valid.")
